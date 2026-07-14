import java.io.*;
import java.nio.file.*;
import java.util.*;

// --- Data Structures ---

// A single note: a short name plus its text content. Wrapped in its own
// class so notes could later gain more metadata (timestamps, tags, etc.)
// without breaking serialization.
class Note implements Serializable {
    private static final long serialVersionUID = 1L;
    private String name;
    private String content;

    public Note(String name, String content) {
        this.name = name;
        this.content = content;
    }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    @Override
    public String toString() { return name + ": " + content; }
}

// A page holds an ordered list of notes.
class Page implements Serializable {
    private static final long serialVersionUID = 1L;
    private String name;
    private List<Note> notes;

    public Page(String name) {
        this.name = name;
        this.notes = new ArrayList<>();
    }
    public String getName() { return name; }
    public List<Note> getNotes() { return notes; }

    public void addNote(Note note) { notes.add(note); }

    // Index-based lookup; returns null instead of throwing so callers
    // can just check for null on bad input.
    public Note getNote(int index) {
        if (index >= 0 && index < notes.size()) return notes.get(index);
        return null;
    }
    public void removeNote(int index) {
        if (index >= 0 && index < notes.size()) notes.remove(index);
        else System.out.println("Invalid note index.");
    }
}

// A book holds an ordered list of pages. Books are the unit that gets
// serialized to its own .book file on disk (see saveCurrentBook/loadBook).
class Book implements Serializable {
    private static final long serialVersionUID = 1L;
    private String name;
    private List<Page> pages;

    public Book(String name) {
        this.name = name;
        this.pages = new ArrayList<>();
    }
    public String getName() { return name; }
    public List<Page> getPages() { return pages; }

    public void addPage(Page page) { pages.add(page); }
    public void removePage(String pageName) {
        pages.removeIf(p -> p.getName().equalsIgnoreCase(pageName));
    }

    // Case-insensitive lookup by page name.
    public Page getPage(String name) {
        for (Page p : pages) {
            if (p.getName().equalsIgnoreCase(name)) return p;
        }
        return null;
    }
}

// --- Configuration / Index Registry ---

// Persistent app-wide settings, saved to CONFIG_FILE. Acts as an index
// mapping book names -> file paths so books don't need to be scanned
// from disk on every startup.
class AppConfig implements Serializable {
    private static final long serialVersionUID = 1L;
    public String defaultDirectory;
    public String defaultEditor; // NEW: Stores the user's preferred text editor
    public Map<String, String> rootBooks = new HashMap<>(); // books not inside any library
    public Map<String, Map<String, String>> libraries = new HashMap<>(); // libraryName -> (bookName -> path)
}

// --- Main Application ---

// Terminal note-taking app organized as Library > Book > Page > Notes.
// State is persisted via Java serialization: one AppConfig registry file
// plus one .book file per book.
public class NotebookApp {
    private static final String CONFIG_FILE = "bookDirectories.dat";
    private static AppConfig config;

    // Tracks where the user currently "is" in the hierarchy (like a cwd).
    private static String currentLibrary = null;
    private static Book currentBook = null;
    private static Page currentPage = null;

    private static Scanner scanner = new Scanner(System.in);

    public static void main(String[] args) {
        System.out.println("Welcome to the Terminal Note Keeper!");
        initializeConfig();

        System.out.println("Type 'help' for commands or 'exit' to quit.");

        // Main REPL loop: read a command, dispatch it, repeat forever.
        while (true) {
            System.out.print("\n" + getPrompt() + "> ");
            String input = scanner.nextLine().trim();
            if (input.isEmpty()) continue;

            String[] parts = input.split(" ", 2);
            String command = parts[0].toLowerCase();
            String argsStr = parts.length > 1 ? parts[1] : "";

            try {
                handleCommand(command, argsStr);
            } catch (Exception e) {
                // Catch-all so a bad command never crashes the whole session.
                System.out.println("Error processing command: " + e.getMessage());
            }
        }
    }

    // Loads the config file if present, otherwise runs first-time setup
    // (ask for default save directory, pick a default editor).
    private static void initializeConfig() {
        File file = new File(CONFIG_FILE);
        if (file.exists()) {
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
                config = (AppConfig) ois.readObject();
                // Fallback if loading an older config file that doesn't have an editor set
                if (config.defaultEditor == null) setDefaultOSEditor();
                return;
            } catch (Exception e) {
                System.out.println("Notice: Registry structure updated or missing. Creating a new one.");
            }
        }

        System.out.println("\n--- First Initialization ---");
        System.out.print("Enter the default directory path to save your books: ");
        String defDir = scanner.nextLine().trim();

        File dir = new File(defDir);
        if (!dir.exists()) {
            if(dir.mkdirs()) System.out.println("Created directory: " + defDir);
            else System.out.println("Failed to create directory. Books will save to current folder.");
        }

        config = new AppConfig();
        config.defaultDirectory = defDir;
        setDefaultOSEditor();
        saveConfig();
        System.out.println("Setup complete! Default editor set to: " + config.defaultEditor + "\n");
    }

    // Picks a sane default editor based on OS (notepad on Windows, nano elsewhere).
    private static void setDefaultOSEditor() {
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            config.defaultEditor = "notepad"; // Windows fallback
        } else {
            config.defaultEditor = "nano"; // Linux/Mac fallback
        }
    }

    // Persists the AppConfig registry (paths + settings) to disk.
    private static void saveConfig() {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(CONFIG_FILE))) {
            oos.writeObject(config);
        } catch (IOException e) {
            System.out.println("Failed to save configuration: " + e.getMessage());
        }
    }

    // Serializes the currently open book back to its file, first backing
    // up the previous version to <path>.bak in case the write fails or
    // corrupts data.
    private static void saveCurrentBook() {
        if (currentBook == null) return;
        Map<String, String> activeRegistry = getActiveBookRegistry();
        String path = activeRegistry.get(currentBook.getName().toLowerCase());
        if (path == null) return;

        File currentFile = new File(path);
        File backupFile = new File(path + ".bak");

        try {
            if (currentFile.exists()) {
                Files.copy(currentFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(currentFile))) {
                oos.writeObject(currentBook);
            }
        } catch (IOException e) {
            System.out.println("Failed to save book data: " + e.getMessage());
        }
    }

    // Deserializes a Book from disk, or null if missing/corrupted.
    private static Book loadBook(String path) {
        File file = new File(path);
        if (!file.exists()) return null;

        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
            return (Book) ois.readObject();
        } catch (Exception e) {
            System.out.println("Failed to load book: " + e.getMessage());
            return null;
        }
    }

    // Builds the "/library/book/page" style prompt reflecting current location.
    private static String getPrompt() {
        StringBuilder prompt = new StringBuilder("/");
        if (currentLibrary != null) prompt.append(currentLibrary);
        if (currentBook != null) {
            if (currentLibrary != null) prompt.append("/");
            prompt.append(currentBook.getName());
        }
        if (currentPage != null) prompt.append("/").append(currentPage.getName());
        return prompt.toString();
    }

    // Returns the book registry (name -> path) relevant to the current
    // location: root-level books, or the books of the open library.
    private static Map<String, String> getActiveBookRegistry() {
        if (currentLibrary == null) return config.rootBooks;
        return config.libraries.get(currentLibrary);
    }

    // --- The External Editor Magic ---

    // Writes content to a temp file, hands terminal control to the user's
    // configured editor (vim/nano/etc.), waits for it to close, then reads
    // back whatever was saved. Used by both addnote (empty text) and editnote.
    private static String openInExternalEditor(String initialContent) {
        try {
            // 1. Create a temporary text file
            File tempFile = File.createTempFile("notekeeper_", ".txt");
            if (initialContent != null && !initialContent.isEmpty()) {
                Files.write(tempFile.toPath(), initialContent.getBytes());
            }

            // 2. Launch the user's preferred editor pointed at the temp file
            ProcessBuilder pb = new ProcessBuilder(config.defaultEditor, tempFile.getAbsolutePath());
            pb.inheritIO(); // This passes control of the terminal to Vim/Nano

            Process p = pb.start();
            p.waitFor(); // Wait for the user to close the editor

            // 3. Read the file, clean up, and return the text
            String updatedContent = new String(Files.readAllBytes(tempFile.toPath())).trim();
            tempFile.delete();

            return updatedContent;
        } catch (Exception e) {
            System.out.println("Failed to open external editor (" + config.defaultEditor + "). " + e.getMessage());
            System.out.println("Try changing your editor using the 'seteditor' command.");
            return initialContent; // return original content if it fails so data isn't lost
        }
    }

    // Central command dispatcher for the REPL. Each case implements one
    // command; grouped by hierarchy level (global/editor/library/book/page/note).
    private static void handleCommand(String command, String args) {
        switch (command) {
            case "help": printHelp(); break;
            case "exit":
                System.out.println("Goodbye!");
                System.exit(0);
                break;
            case "up":
            case "back":
                // Step back up one level: page -> book -> library -> top.
                if (currentPage != null) currentPage = null;
                else if (currentBook != null) currentBook = null;
                else if (currentLibrary != null) currentLibrary = null;
                else System.out.println("You are already at the top level.");
                break;

            // --- Editor Config ---
            case "seteditor":
                if (args.isEmpty()) System.out.println("Current editor is: " + config.defaultEditor + "\nUsage: seteditor <vim|nano|micro|notepad>");
                else {
                    config.defaultEditor = args.toLowerCase();
                    saveConfig();
                    System.out.println("Default editor changed to: " + config.defaultEditor);
                }
                break;

            // --- Library Commands ---
            case "mklib":
                if (args.isEmpty()) System.out.println("Usage: mklib <name>");
                else {
                    String cleanName = args.toLowerCase();
                    if (config.libraries.containsKey(cleanName)) System.out.println("Library already exists.");
                    else {
                        config.libraries.put(cleanName, new HashMap<>());
                        saveConfig();
                        System.out.println("Library '" + args + "' created.");
                    }
                }
                break;
            case "mkcdlib": // make (if needed) and enter a library in one step
                if (args.isEmpty()) System.out.println("Usage: mkcdlib <name>");
                else {
                    String cleanName = args.toLowerCase();
                    if (!config.libraries.containsKey(cleanName)) {
                        config.libraries.put(cleanName, new HashMap<>());
                        saveConfig();
                    }
                    currentLibrary = cleanName;
                }
                break;
            case "lslibs":
                if (config.libraries.isEmpty()) System.out.println("No libraries exist.");
                else config.libraries.keySet().forEach(lib -> System.out.println("- " + lib));
                break;
            case "openlib":
                if (args.isEmpty()) System.out.println("Usage: openlib <name>");
                else {
                    String cleanName = args.toLowerCase();
                    if (config.libraries.containsKey(cleanName)) currentLibrary = cleanName;
                    else System.out.println("Library not found.");
                }
                break;
            case "cdlib":
                if (args.isEmpty()) System.out.println("Usage: cdlib <name>");
                else {
                    String cleanName = args.toLowerCase();
                    if (config.libraries.containsKey(cleanName)) currentLibrary = cleanName;
                    else System.out.println("Library not found.");
                }
                break;
            case "rmlib":
                // Deletes the library along with every book file (and its
                // .bak backup) it contains, then clears current location
                // if we were inside the deleted library.
                if (args.isEmpty()) System.out.println("Usage: rmlib <name>");
                else {
                    String cleanName = args.toLowerCase();
                    if (config.libraries.containsKey(cleanName)) {
                        Map<String, String> libBooks = config.libraries.get(cleanName);
                        for (String path : libBooks.values()) {
                            new File(path).delete();
                            new File(path + ".bak").delete();
                        }
                        config.libraries.remove(cleanName);
                        saveConfig();
                        if (currentLibrary != null && currentLibrary.equalsIgnoreCase(cleanName)) {
                            currentLibrary = null; currentBook = null; currentPage = null;
                        }
                        System.out.println("Library and all its contained books deleted.");
                    } else System.out.println("Library not found.");
                }
                break;

            // --- Book Commands ---
            case "mkbook":
                if (args.isEmpty()) System.out.println("Usage: mkbook <name>");
                else {
                    String cleanName = args.toLowerCase();
                    Map<String, String> activeRegistry = getActiveBookRegistry();
                    if (activeRegistry.containsKey(cleanName)) {
                        System.out.println("A book with this name already exists.");
                        break;
                    }
                    // Books live under defaultDirectory, nested in a subfolder
                    // named after the library if one is open.
                    String directory = currentLibrary == null ? config.defaultDirectory : Paths.get(config.defaultDirectory, currentLibrary).toString();
                    new File(directory).mkdirs();
                    String path = Paths.get(directory, args + ".book").toString();
                    createBookAndRegister(args, path, activeRegistry);
                }
                break;
            case "mkcdbook": // make (if needed) and enter a book in one step
                if (args.isEmpty()) System.out.println("Usage: mkcdbook <name>");
                else {
                    String cleanName = args.toLowerCase();
                    Map<String, String> activeRegistry = getActiveBookRegistry();

                    if (activeRegistry.containsKey(cleanName)) {
                        currentBook = loadBook(activeRegistry.get(cleanName));
                    } else {
                        String directory = currentLibrary == null ? config.defaultDirectory : Paths.get(config.defaultDirectory, currentLibrary).toString();
                        new File(directory).mkdirs();
                        String path = Paths.get(directory, args + ".book").toString();
                        createBookAndRegister(args, path, activeRegistry);
                        currentBook = loadBook(path);
                    }
                }
                break;
            case "lsbooks":
                Map<String, String> activeRegistryForLs = getActiveBookRegistry();
                if (activeRegistryForLs.isEmpty()) System.out.println("No books found here.");
                else activeRegistryForLs.forEach((name, path) -> System.out.println("- " + name));
                break;
            case "openbook":
                if (args.isEmpty()) System.out.println("Usage: openbook <name>");
                else {
                    String bookPath = getActiveBookRegistry().get(args.toLowerCase());
                    if (bookPath != null) {
                        Book b = loadBook(bookPath);
                        if (b != null) currentBook = b;
                        else System.out.println("File missing or corrupted.");
                    } else System.out.println("Book not found.");
                }
                break;
            case "cdbook":
                if (args.isEmpty()) System.out.println("Usage: cdbook <name>");
                else {
                    String bookPath = getActiveBookRegistry().get(args.toLowerCase());
                    if (bookPath != null) {
                        Book b = loadBook(bookPath);
                        if (b != null) currentBook = b;
                        else System.out.println("File missing or corrupted.");
                    } else System.out.println("Book not found.");
                }
                break;
            case "rmbook":
                if (args.isEmpty()) System.out.println("Usage: rmbook <name>");
                else {
                    String target = args.toLowerCase();
                    Map<String, String> activeReg = getActiveBookRegistry();
                    if (activeReg.containsKey(target)) {
                        String basePath = activeReg.get(target);
                        new File(basePath).delete();
                        new File(basePath + ".bak").delete();
                        activeReg.remove(target);
                        saveConfig();
                        if (currentBook != null && currentBook.getName().equalsIgnoreCase(args)) {
                            currentBook = null; currentPage = null;
                        }
                        System.out.println("Book deleted.");
                    } else System.out.println("Book not found.");
                }
                break;

            // --- Page Commands ---
            case "mkpage":
                if (currentBook == null) System.out.println("Open a book first.");
                else if (args.isEmpty()) System.out.println("Usage: mkpage <name>");
                else { currentBook.addPage(new Page(args)); saveCurrentBook(); }
                break;
            case "mkcdpage": // make (if needed) and enter a page in one step
                if (currentBook == null) System.out.println("Open a book first.");
                else if (args.isEmpty()) System.out.println("Usage: mkcdpage <name>");
                else {
                    Page existing = currentBook.getPage(args);
                    if (existing != null) {
                        currentPage = existing;
                    } else {
                        Page newPage = new Page(args);
                        currentBook.addPage(newPage);
                        saveCurrentBook();
                        currentPage = newPage;
                    }
                }
                break;
            case "lspages":
                if (currentBook == null) System.out.println("Open a book first.");
                else if (currentBook.getPages().isEmpty()) System.out.println("No pages in this book.");
                else currentBook.getPages().forEach(p -> System.out.println("- " + p.getName()));
                break;
            case "openpage":
                if (currentBook == null) System.out.println("Open a book first.");
                else {
                    Page p = currentBook.getPage(args);
                    if (p != null) currentPage = p;
                    else System.out.println("Page not found.");
                }
                break;
            case "cdpage":
                if (currentBook == null) System.out.println("Open a book first.");
                else {
                    Page p = currentBook.getPage(args);
                    if (p != null) currentPage = p;
                    else System.out.println("Page not found.");
                }
                break;
            case "rmpage":
                if (currentBook != null) { currentBook.removePage(args); saveCurrentBook(); }
                if (currentPage != null && currentPage.getName().equalsIgnoreCase(args)) currentPage = null;
                break;

            // --- Note Commands ---
            case "addnote":
                if (currentPage == null) {
                    System.out.println("Open a page first.");
                } else if (args.isEmpty()) {
                    System.out.println("Usage: addnote <name> [content]");
                } else {
                    // First token is the note's name; anything after is the
                    // (optional) inline content.
                    String[] noteParts = args.split(" ", 2);
                    String noteName = noteParts[0];
                    String noteContent = noteParts.length > 1 ? noteParts[1] : "";

                    if (noteContent.isEmpty()) {
                        // Smart Add: No content provided? Open the editor!
                        String newContent = openInExternalEditor("");
                        if (!newContent.isEmpty()) {
                            currentPage.addNote(new Note(noteName, newContent));
                            saveCurrentBook();
                            System.out.println("Note added.");
                        } else {
                            System.out.println("Note discarded (empty).");
                        }
                    } else {
                        // Fast Add: Content provided? Just add it directly.
                        currentPage.addNote(new Note(noteName, noteContent));
                        saveCurrentBook();
                        System.out.println("Quick note added.");
                    }
                }
                break;
            case "mknote":
                if (currentPage == null) {
                    System.out.println("Open a page first.");
                } else if (args.isEmpty()) {
                    System.out.println("Usage: addnote <name> [content]");
                } else {
                    // First token is the note's name; anything after is the
                    // (optional) inline content.
                    String[] noteParts = args.split(" ", 2);
                    String noteName = noteParts[0];
                    String noteContent = noteParts.length > 1 ? noteParts[1] : "";

                    if (noteContent.isEmpty()) {
                        // Smart Add: No content provided? Open the editor!
                        String newContent = openInExternalEditor("");
                        if (!newContent.isEmpty()) {
                            currentPage.addNote(new Note(noteName, newContent));
                            saveCurrentBook();
                            System.out.println("Note added.");
                        } else {
                            System.out.println("Note discarded (empty).");
                        }
                    } else {
                        // Fast Add: Content provided? Just add it directly.
                        currentPage.addNote(new Note(noteName, noteContent));
                        saveCurrentBook();
                        System.out.println("Quick note added.");
                    }
                }
                break;

            case "editnote":
                if (currentPage == null) {
                    System.out.println("Open a page first.");
                } else if (args.isEmpty()) {
                    System.out.println("Usage: editnote <index>");
                } else {
                    try {
                        int index = Integer.parseInt(args);
                        Note note = currentPage.getNote(index);
                        if (note != null) {
                            String updatedContent = openInExternalEditor(note.getContent());
                            if (!updatedContent.isEmpty()) {
                                note.setContent(updatedContent);
                                saveCurrentBook();
                                System.out.println("Note updated.");
                            } else {
                                System.out.println("Note unchanged (cannot be completely empty).");
                            }
                        } else {
                            System.out.println("Invalid note index.");
                        }
                    } catch (NumberFormatException e) {
                        System.out.println("Usage: editnote <index>");
                    }
                }
                break;

            case "lsnotes":
                if (currentPage == null) System.out.println("Open a page first.");
                else if (currentPage.getNotes().isEmpty()) System.out.println("No notes on this page.");
                else {
                    List<Note> notes = currentPage.getNotes();
                    for (int i = 0; i < notes.size(); i++) {
                        Note n = notes.get(i);
                        System.out.println("[" + i + "] " + n.getName() + ": " + n.getContent());
                    }
                }
                break;
            case "rmnote":
                if (currentPage == null) System.out.println("Open a page first.");
                else {
                    try { currentPage.removeNote(Integer.parseInt(args)); saveCurrentBook(); }
                    catch (NumberFormatException e) { System.out.println("Usage: rmnote <index>"); }
                }
                break;
            default:
                System.out.println("Unknown command. Type 'help'.");
        }
    }

    // Creates a new Book, writes it to disk, and registers its path in
    // the given registry (root books or a specific library's books).
    private static void createBookAndRegister(String name, String path, Map<String, String> activeRegistry) {
        Book newBook = new Book(name);
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(path))) {
            oos.writeObject(newBook);
            activeRegistry.put(name.toLowerCase(), path);
            saveConfig();
        } catch (IOException e) {
            System.out.println("Failed to create book file: " + e.getMessage());
        }
    }

    private static void printHelp() {
        System.out.println("--- Global Commands ---");
        System.out.println("up / back          : Go up one level in hierarchy");
        System.out.println("seteditor <name>   : Set your preferred external editor (vim, nano, micro, etc.)");
        System.out.println("exit               : Quit the program");

        System.out.println("\n--- Fast Navigation (Create & Open) ---");
        System.out.println("mkcdlib <name>     : Make and enter a library");
        System.out.println("mkcdbook <name>    : Make and enter a book");
        System.out.println("mkcdpage <name>    : Make and enter a page");

        System.out.println("\n--- Library Commands ---");
        System.out.println("mklib / lslibs / openlib = cdpage / rmlib");

        System.out.println("\n--- Book Commands ---");
        System.out.println("mkbook / lsbooks / openbook = cdpage / rmbook");

        System.out.println("\n--- Page Commands ---");
        System.out.println("mkpage / lspages / openpage = cdpage / rmpage");

        System.out.println("\n--- Note Commands ---");
        System.out.println("addnote <name>          : Opens external editor to write a new note called <name>");
        System.out.println("addnote <name> <text>   : Quick-adds a note called <name> with <text> directly");
        System.out.println("mknote <name>          : Opens external editor to write a new note called <name>");
        System.out.println("mknote <name> <text>   : Quick-adds a note called <name> with <text> directly");
        System.out.println("editnote <index>   : Opens an existing note in the external editor");
        System.out.println("lsnotes            : List all notes with their index");
        System.out.println("rmnote <index>     : Remove a note by index");
    }
}
