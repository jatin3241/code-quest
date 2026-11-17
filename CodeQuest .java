import javax.swing.*;
import javax.swing.Timer;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.util.*;
import java.util.List;

/**
 * CODE QUEST - a tiny platformer where you learn by solving coding trivia.
 *
 *   1. Pick the language you want to learn.
 *   2. Run and jump through each level.
 *   3. Reach the terminal, press E, answer the question.
 *   4. Correct answer = the gate unlocks. Jump through the portal to the next level!
 *
 * Compile:  javac CodeQuest.java
 * Run:      java CodeQuest
 * (or just: java CodeQuest.java)
 *
 * Controls: A/D or Left/Right = move | W/Up/Space = jump | E = interact | 1-4 = answer | Esc = menu
 */
public class CodeQuest extends JPanel
        implements ActionListener, KeyListener, MouseListener, MouseMotionListener {

    // ============================================================
    //  Constants
    // ============================================================
    static final int W = 960, H = 600;
    static final double GROUND_Y = 470;
    static final double GRAVITY = 0.62, MOVE = 4.7, JUMP_V = -12.9, MAX_FALL = 15;
    static final double PW = 28, PH = 40;               // player size
    static final int LEVELS = 6, MAX_HEARTS = 5;

    enum State { MENU, PLAY, QUIZ, LEVEL_DONE, GAME_OVER, VICTORY }

    static final Color[][] SKY = {
        {new Color(0x4FA8FF), new Color(0xCFEFFF)},
        {new Color(0x56B6E8), new Color(0xFFF1C9)},
        {new Color(0xFF9A62), new Color(0xFFE3A3)},
        {new Color(0xE0568E), new Color(0xFFB874)},
        {new Color(0x2B2F77), new Color(0x8A4FA5)},
        {new Color(0x0B1033), new Color(0x2A2A6A)}
    };

    static final Font F_TITLE = new Font("SansSerif", Font.BOLD, 68);
    static final Font F_BIG = new Font("SansSerif", Font.BOLD, 44);
    static final Font F_H1 = new Font("SansSerif", Font.BOLD, 24);
    static final Font F_H2 = new Font("SansSerif", Font.BOLD, 20);
    static final Font F_TXT = new Font("SansSerif", Font.PLAIN, 18);
    static final Font F_SMALL = new Font("SansSerif", Font.PLAIN, 14);
    static final Font F_WHY = new Font("SansSerif", Font.PLAIN, 16);
    static final Font F_MONO = new Font("Monospaced", Font.BOLD, 17);

    // ============================================================
    //  Data: questions, languages, levels, particles
    // ============================================================
    static final class Q {
        final String text, code, why;
        final int ans;
        final String[] opts;
        Q(String text, String code, String why, int ans, String[] opts) {
            this.text = text; this.code = code; this.why = why; this.ans = ans; this.opts = opts;
        }
    }

    static final class Lang {
        final String name, tag;
        final Color color;
        final List<Q> qs = new ArrayList<>();
        Lang(String name, String tag, Color color) { this.name = name; this.tag = tag; this.color = color; }
        /** plain question */
        Lang q(String text, String why, int ans, String... opts) { qs.add(new Q(text, null, why, ans, opts)); return this; }
        /** question with a code snippet */
        Lang c(String text, String code, String why, int ans, String... opts) { qs.add(new Q(text, code, why, ans, opts)); return this; }
    }

    static final class Level {
        final List<Rectangle2D.Double> ground = new ArrayList<>();
        final List<Rectangle2D.Double> plats = new ArrayList<>();
        final List<double[]> coins = new ArrayList<>();    // x, y, collected(0/1)
        final List<double[]> spikes = new ArrayList<>();   // x, width
        double width, termX, gateX, exitX, gateAnim;
        boolean solved, gateOpen;
    }

    static final class P {
        double x, y, vx, vy, size;
        int life, max;
        Color c;
    }

    // ============================================================
    //  Game state
    // ============================================================
    State state = State.MENU;
    final Lang[] langs = buildLangs();
    int menuSel = 0;
    Lang lang;
    List<Q> deck = new ArrayList<>();
    int deckPos, levelIdx, hearts, score, streak, solved, wrong, quizNo;
    Level level;

    double px, py, vx, vy, camX, squash, lastSafeX, lastSafeY;
    boolean onGround, facingRight = true;
    int coyote, jumpBuffer, invuln, shake;

    Q curQ;
    int[] order = {0, 1, 2, 3};
    int chosen = -1, hoverOpt = -1, lastGain;
    boolean revealed, lastCorrect;
    final Rectangle[] optRects = new Rectangle[4];

    int tick, doneTimer, toastTimer;
    float fade = 0f;
    String toast = "";

    final List<P> parts = new ArrayList<>();
    final Set<Integer> down = new HashSet<>();
    final Random rnd = new Random();
    final double[][] stars = new double[90][2];
    final double[][] clouds = new double[7][3];

    // ============================================================
    //  Setup
    // ============================================================
    public CodeQuest() {
        setPreferredSize(new Dimension(W, H));
        setFocusable(true);
        addKeyListener(this);
        addMouseListener(this);
        addMouseMotionListener(this);
        addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { down.clear(); }
        });
        Random r = new Random(7);
        for (double[] s : stars) { s[0] = r.nextDouble() * W; s[1] = r.nextDouble() * 330; }
        for (double[] c : clouds) { c[0] = r.nextDouble() * (W + 300); c[1] = 50 + r.nextDouble() * 160; c[2] = 0.7 + r.nextDouble() * 0.9; }
        for (int i = 0; i < optRects.length; i++) optRects[i] = new Rectangle();
        new Timer(16, this).start();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("Code Quest - learn to code by playing");
            f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            CodeQuest g = new CodeQuest();
            f.setContentPane(g);
            f.pack();
            f.setResizable(false);
            f.setLocationRelativeTo(null);
            f.setVisible(true);
            g.requestFocusInWindow();
        });
    }

    // ============================================================
    //  Question bank  (add your own! q = plain question, c = with code snippet)
    //  q(text, explanation, indexOfCorrectOption, option0, option1, option2, option3)
    // ============================================================
    static Lang[] buildLangs() {
        Lang java = new Lang("Java", "JV", new Color(0xF0883E))
            .q("Which keyword makes a class inherit from another class?",
               "'extends' creates a subclass. 'implements' is used for interfaces.", 0,
               "extends", "implements", "inherits", "super")
            .c("What does this print?", "int x = 5;\nSystem.out.println(x++ + ++x);",
               "x++ gives 5 (then x becomes 6), ++x makes it 7 and gives 7. So 5 + 7 = 12.", 2,
               "10", "11", "12", "13")
            .q("Which collection does NOT allow duplicate elements?",
               "A Set (like HashSet) stores unique elements only. Lists keep duplicates.", 2,
               "ArrayList", "LinkedList", "HashSet", "Vector")
            .q("What does 'final' do to a variable?",
               "A final variable can be assigned only once, so it can never be reassigned.", 1,
               "Makes it static", "Prevents it from being reassigned", "Makes it private", "Deletes it after use")
            .q("Which is the entry point of a Java program?",
               "The JVM starts execution at public static void main(String[] args).", 0,
               "public static void main(String[] args)", "public void main()", "static int start()", "void run(String[] args)")
            .q("Which of these is NOT a primitive type in Java?",
               "String is a class (an object). int, boolean and double are primitives.", 2,
               "int", "boolean", "String", "double")
            .c("What does this print?", "String a = \"hi\";\nString b = new String(\"hi\");\nSystem.out.println(a == b);",
               "== compares object references, and new String creates a different object. Use equals() to compare text.", 1,
               "true", "false", "hi", "Compile error")
            .q("Which exception is thrown for an invalid array index?",
               "Accessing an index outside 0..length-1 throws ArrayIndexOutOfBoundsException.", 1,
               "NullPointerException", "ArrayIndexOutOfBoundsException", "ClassCastException", "IllegalStateException");

        Lang py = new Lang("Python", "PY", new Color(0x3C8DDE))
            .q("Which keyword defines a function in Python?",
               "def name(args): starts a function. lambda creates small anonymous functions.", 1,
               "function", "def", "fun", "define")
            .c("What does this print?", "print(2 ** 3)",
               "** is the exponent operator, so 2 ** 3 = 2 x 2 x 2 = 8.", 1,
               "6", "8", "9", "23")
            .q("Which of these data types is immutable?",
               "Tuples cannot be changed after creation. Lists, dicts and sets are mutable.", 3,
               "list", "dict", "set", "tuple")
            .c("What does this print?", "x = [10, 20, 30]\nprint(x[-1])",
               "Negative indexes count from the end, so x[-1] is the last element: 30.", 2,
               "10", "20", "30", "IndexError")
            .q("How do you write a single-line comment in Python?",
               "Python comments start with #. Everything after it on that line is ignored.", 1,
               "// comment", "# comment", "-- comment", "/* comment */")
            .c("What does this print?", "print(\"Hi\" * 3)",
               "Multiplying a string repeats it, so \"Hi\" * 3 gives HiHiHi.", 1,
               "Hi3", "HiHiHi", "Hi Hi Hi", "TypeError")
            .q("Which list method adds an item to the end?",
               "list.append(x) adds x at the end. insert(i, x) adds at a given position.", 2,
               "add()", "push()", "append()", "attach()")
            .c("What does this print?", "for i in range(3):\n    print(i, end=\" \")",
               "range(3) gives 0, 1, 2 (it stops before 3). end=\" \" keeps the output on one line.", 1,
               "1 2 3", "0 1 2", "0 1 2 3", "1 2");

        Lang js = new Lang("JavaScript", "JS", new Color(0xF7DF1E))
            .q("Which keyword declares a block-scoped variable you can reassign?",
               "let is block-scoped and reassignable. const can't be reassigned; var is function-scoped.", 1,
               "var", "let", "const", "dim")
            .c("What does this print?", "console.log(typeof null);",
               "A famous quirk: typeof null returns \"object\" (an old bug kept for compatibility).", 2,
               "\"null\"", "\"undefined\"", "\"object\"", "\"number\"")
            .c("What does this print?", "console.log(\"5\" + 3);",
               "When one side of + is a string, JS concatenates. The number 3 becomes text, giving \"53\".", 1,
               "8", "53", "NaN", "Error")
            .q("Which method turns a JSON string into a JavaScript object?",
               "JSON.parse(text) builds an object. JSON.stringify(obj) does the opposite.", 1,
               "JSON.stringify()", "JSON.parse()", "JSON.decode()", "Object.from()")
            .q("What is the difference between === and == ?",
               "=== compares value AND type with no coercion. == converts types first (0 == \"0\" is true).", 1,
               "No difference", "=== also checks the type", "=== only checks references", "== is faster and safer")
            .q("Which array method returns a NEW array of items that pass a test?",
               "filter() keeps only the elements for which the callback returns true.", 2,
               "map()", "forEach()", "filter()", "reduce()")
            .c("What does this print?", "const nums = [1, 2, 3];\nconsole.log(nums.map(n => n * 2));",
               "map() transforms every element and returns a new array: [2, 4, 6].", 1,
               "[1, 2, 3]", "[2, 4, 6]", "[1, 4, 9]", "12")
            .q("What do async/await help you write?",
               "async/await is syntax on top of Promises that makes asynchronous code read like normal code.", 1,
               "Multi-threaded code", "Cleaner asynchronous code", "Type-safe code", "Faster CPU loops");

        Lang cpp = new Lang("C++", "C++", new Color(0x8E6CFF))
            .q("Which operator accesses a member through a pointer?",
               "ptr->member is shorthand for (*ptr).member. The dot is used on objects and references.", 1,
               ".", "->", "::", "&")
            .q("Which header do you include to use std::cout?",
               "<iostream> declares cin, cout and cerr for console input and output.", 1,
               "<stdio.h>", "<iostream>", "<string>", "<cstdlib>")
            .c("What does this print?", "int a = 10;\nint* p = &a;\nstd::cout << *p;",
               "&a is the address of a. *p dereferences the pointer and reads the value: 10.", 1,
               "The address of a", "10", "a", "Compile error")
            .q("What does 'delete' do in C++?",
               "delete frees memory that was allocated with new, which prevents memory leaks.", 1,
               "Removes a file", "Frees memory allocated with new", "Removes a variable name", "Clears the console")
            .q("Which keyword makes a variable read-only?",
               "const prevents modification after initialization. The compiler enforces it.", 0,
               "const", "static", "final", "readonly")
            .q("What is std::vector?",
               "std::vector is a resizable array that manages its own memory and can grow at runtime.", 1,
               "A fixed-size C array", "A dynamic array that can grow", "A hash map", "A linked list")
            .c("What does this print?", "int x = 7;\nstd::cout << x / 2;",
               "Both operands are integers, so it's integer division and the fraction is dropped: 3.", 1,
               "3.5", "3", "4", "2")
            .q("What lets a class redefine operators such as + or == ?",
               "Operator overloading lets your own types work with the built-in operator syntax.", 1,
               "Templates", "Operator overloading", "Namespaces", "Lambdas");

        Lang sql = new Lang("SQL", "SQL", new Color(0x2EC4A6))
            .q("Which statement reads data from a table?",
               "SELECT chooses which columns and rows to return from a table.", 1,
               "GET", "SELECT", "FETCH", "OPEN")
            .q("Which clause filters rows by a condition?",
               "WHERE filters rows before grouping. HAVING filters groups after GROUP BY.", 2,
               "ORDER BY", "GROUP BY", "WHERE", "LIMIT")
            .c("What does this query return?", "SELECT COUNT(*) FROM users;",
               "COUNT(*) returns how many rows are in the table, as a single number.", 1,
               "All user rows", "The number of rows in users", "The first user", "The column names")
            .q("Which JOIN returns only rows that match in BOTH tables?",
               "INNER JOIN keeps only matching rows. LEFT JOIN also keeps unmatched rows from the left table.", 1,
               "LEFT JOIN", "INNER JOIN", "FULL OUTER JOIN", "CROSS JOIN")
            .q("Which keyword removes duplicate rows from a result?",
               "SELECT DISTINCT returns each unique row (or value) only once.", 1,
               "UNIQUE", "DISTINCT", "DIFFERENT", "SINGLE")
            .q("Which statement changes existing rows in a table?",
               "UPDATE ... SET ... WHERE ... edits rows. ALTER TABLE changes the table's structure instead.", 2,
               "MODIFY", "ALTER ROW", "UPDATE", "CHANGE")
            .q("A PRIMARY KEY column must be...",
               "A primary key uniquely identifies each row, so values must be unique and never NULL.", 0,
               "Unique and not NULL", "Always text", "Sorted descending", "Allowed to repeat")
            .q("Which clause sorts the results?",
               "ORDER BY sorts ascending by default. Add DESC for descending order.", 1,
               "SORT BY", "ORDER BY", "GROUP BY", "ARRANGE");

        return new Lang[]{java, py, js, cpp, sql};
    }

    // ============================================================
    //  Game flow
    // ============================================================
    void startGame(Lang l) {
        lang = l;
        deck = new ArrayList<>(l.qs);
        Collections.shuffle(deck, rnd);
        deckPos = 0;
        hearts = 3; score = 0; streak = 0; solved = 0; wrong = 0; quizNo = 0;
        startLevel(0);
        state = State.PLAY;
    }

    void startLevel(int i) {
        levelIdx = i;
        level = buildLevel(i);
        px = 80; py = GROUND_Y - PH; vx = 0; vy = 0; camX = 0;
        onGround = false; coyote = 0; jumpBuffer = 0; invuln = 0; squash = 0;
        lastSafeX = px; lastSafeY = py;
        fade = 1f;
        parts.clear();
        say("Level " + (i + 1) + ": reach the terminal and answer to unlock the gate!", 260);
    }

    void toMenu() { state = State.MENU; parts.clear(); }

    void say(String s, int frames) { toast = s; toastTimer = frames; }

    // ---------- level layout (gets harder with each level) ----------
    Level buildLevel(int i) {
        Level L = new Level();
        double x = 0;

        // Zone 1: spawn area
        ground(L, 0, 700);
        floater(L, 170, 90, 110);
        floater(L, 310, 170, 110);
        floater(L, 450, 90, 110);
        coinRow(L, 70, GROUND_Y - 30, 3, 36);
        if (i >= 1) spikes(L, 360, i >= 3 ? 54 : 36);
        x = 700;

        // Pit A
        double pa = 110 + i * 8;
        if (i < 3) floater(L, x + (pa - 70) / 2, 100, 70);
        x += pa;

        // Zone 2
        double z2 = x;
        ground(L, z2, 600);
        floater(L, z2 + 110, 100, 110);
        floater(L, z2 + 250, 175, 110);
        coinRow(L, z2 + 60, GROUND_Y - 30, 3, 36);
        if (i >= 2) spikes(L, z2 + 430, 36);
        x += 600;

        // Pit B
        double pb = 110 + i * 8;
        if (i < 3) floater(L, x + (pb - 70) / 2, 100, 70);
        x += pb;

        // Zone 3: terminal -> gate -> portal
        double z3 = x;
        ground(L, z3, 1150);
        L.termX = z3 + 150;
        floater(L, z3 + 260, 110, 110);
        if (i >= 1) spikes(L, z3 + 400, 36);
        if (i >= 3) spikes(L, z3 + 520, 36);
        if (i >= 5) spikes(L, z3 + 620, 36);
        coinRow(L, z3 + 330, GROUND_Y - 30, 3, 36);
        L.gateX = z3 + 760;
        L.exitX = z3 + 980;
        L.width = z3 + 1150;
        return L;
    }

    void ground(Level L, double x, double w) { L.ground.add(new Rectangle2D.Double(x, GROUND_Y, w, 200)); }
    void spikes(Level L, double x, double w) { L.spikes.add(new double[]{x, w}); }
    void coinRow(Level L, double x, double y, int n, double gap) {
        for (int k = 0; k < n; k++) L.coins.add(new double[]{x + k * gap, y, 0});
    }
    void floater(Level L, double x, double h, double w) {
        L.plats.add(new Rectangle2D.Double(x, GROUND_Y - h, w, 16));
        int n = Math.max(1, (int) (w / 36));
        coinRow(L, x + (w - (n - 1) * 36) / 2, GROUND_Y - h - 26, n, 36);
    }

    // ============================================================
    //  Update loop
    // ============================================================
    @Override public void actionPerformed(ActionEvent e) {
        tick++;
        updateParticles();
        if (toastTimer > 0) toastTimer--;
        if (state == State.PLAY) updatePlay();
        else if (state == State.LEVEL_DONE) updateLevelDone();
        repaint();
    }

    void updateParticles() {
        for (Iterator<P> it = parts.iterator(); it.hasNext(); ) {
            P p = it.next();
            p.x += p.vx; p.y += p.vy; p.vy += 0.15;
            if (--p.life <= 0) it.remove();
        }
        if (shake > 0) shake--;
        squash = squash > 0.01 ? squash * 0.85 : 0;
        if (state == State.VICTORY && tick % 3 == 0) {
            P p = new P();
            p.x = camX + rnd.nextDouble() * W; p.y = -10;
            p.vx = rnd.nextDouble() * 2 - 1; p.vy = rnd.nextDouble() * 2;
            p.size = 5 + rnd.nextDouble() * 5; p.life = p.max = 260;
            p.c = Color.getHSBColor(rnd.nextFloat(), 0.8f, 1f);
            parts.add(p);
        }
        if (fade > 0 && state != State.LEVEL_DONE) fade = Math.max(0, fade - 0.03f);
    }

    void burst(double x, double y, int n, Color c, double power) {
        for (int k = 0; k < n; k++) {
            P p = new P();
            double a = rnd.nextDouble() * Math.PI * 2, s = rnd.nextDouble() * power;
            p.x = x; p.y = y; p.vx = Math.cos(a) * s; p.vy = Math.sin(a) * s - 1.5;
            p.size = 3 + rnd.nextDouble() * 4; p.life = p.max = 30 + rnd.nextInt(30); p.c = c;
            parts.add(p);
        }
    }

    boolean isDown(int k) { return down.contains(k); }

    List<Rectangle2D.Double> solids() {
        List<Rectangle2D.Double> s = new ArrayList<>(level.ground);
        if (!level.gateOpen) s.add(new Rectangle2D.Double(level.gateX, -3000, 26, GROUND_Y + 3000));
        return s;
    }

    boolean hit(Rectangle2D.Double s) {
        return px < s.x + s.width && px + PW > s.x && py < s.y + s.height && py + PH > s.y + 0.001;
    }

    void updatePlay() {
        Level L = level;
        if (invuln > 0) invuln--;
        if (L.gateOpen && L.gateAnim < 1) L.gateAnim = Math.min(1, L.gateAnim + 0.025);

        boolean left = isDown(KeyEvent.VK_LEFT) || isDown(KeyEvent.VK_A);
        boolean right = isDown(KeyEvent.VK_RIGHT) || isDown(KeyEvent.VK_D);
        boolean jumpHeld = isDown(KeyEvent.VK_SPACE) || isDown(KeyEvent.VK_W) || isDown(KeyEvent.VK_UP);
        double target = ((right ? 1 : 0) - (left ? 1 : 0)) * MOVE;
        vx += (target - vx) * (onGround ? 0.30 : 0.18);
        if (target == 0 && Math.abs(vx) < 0.1) vx = 0;
        if (target != 0) facingRight = target > 0;

        // jump with coyote time + input buffer (feels much nicer)
        if (onGround) coyote = 7; else if (coyote > 0) coyote--;
        if (jumpBuffer > 0) jumpBuffer--;
        if (jumpBuffer > 0 && coyote > 0) {
            vy = JUMP_V; onGround = false; coyote = 0; jumpBuffer = 0; squash = 0.25;
            burst(px + PW / 2, py + PH, 6, new Color(255, 255, 255, 200), 2);
        }
        if (vy < 0 && !jumpHeld) vy += GRAVITY * 1.2;     // release early = short hop
        double prevVy = vy;
        vy = Math.min(vy + GRAVITY, MAX_FALL);

        // --- horizontal movement + collisions ---
        px += vx;
        if (px < 0) { px = 0; vx = 0; }
        if (px + PW > L.width) { px = L.width - PW; vx = 0; }
        for (Rectangle2D.Double s : solids()) {
            if (hit(s)) {
                if (vx > 0) px = s.x - PW; else if (vx < 0) px = s.x + s.width;
                vx = 0;
            }
        }

        // --- vertical movement + collisions ---
        boolean wasGround = onGround;
        double oldBottom = py + PH;
        py += vy;
        onGround = false;
        Rectangle2D.Double standing = null;
        for (Rectangle2D.Double s : solids()) {
            if (hit(s)) {
                if (vy > 0) { py = s.y - PH; onGround = true; standing = s; }
                else if (vy < 0) py = s.y + s.height;
                vy = 0;
            }
        }
        for (Rectangle2D.Double s : L.plats) {           // one-way platforms
            if (vy >= 0 && oldBottom <= s.y + 0.01 && py + PH >= s.y && px + PW > s.x && px < s.x + s.width) {
                py = s.y - PH; vy = 0; onGround = true; standing = s;
            }
        }
        if (onGround && !wasGround && prevVy > 3) {
            squash = Math.min(0.4, prevVy * 0.03);
            burst(px + PW / 2, py + PH, 5, new Color(255, 255, 255, 190), 1.8);
        }

        // remember a safe respawn point
        if (onGround && standing != null && L.ground.contains(standing)
                && px > standing.x + 50 && px + PW < standing.x + standing.width - 50 && !nearSpike()) {
            lastSafeX = px; lastSafeY = py;
        }

        // --- hazards ---
        if (invuln == 0) {
            for (double[] sp : L.spikes) {
                if (px + PW > sp[0] + 5 && px < sp[0] + sp[1] - 5 && py + PH > GROUND_Y - 18) {
                    hurt("Ouch! Spikes!");
                    break;
                }
            }
        }
        if (py + PH > H - 30) {                            // fell into the bug pit
            hearts--;
            shake = 12;
            burst(px + PW / 2, H - 40, 18, new Color(255, 120, 40), 4);
            if (hearts <= 0) { state = State.GAME_OVER; return; }
            px = lastSafeX; py = lastSafeY; vx = 0; vy = 0; invuln = 90;
            say("You fell into a bug pit! -1 heart", 120);
        }

        // --- coins ---
        for (double[] c : L.coins) {
            if (c[2] == 0 && Math.abs(px + PW / 2 - c[0]) < 22 && Math.abs(py + PH / 2 - c[1]) < 30) {
                c[2] = 1; score += 10;
                burst(c[0], c[1], 8, new Color(255, 214, 10), 3);
            }
        }

        // --- gate hint ---
        if (!L.gateOpen && px + PW > L.gateX - 40 && toastTimer <= 0)
            say("The gate is locked. Answer the terminal's question first!", 150);

        // --- portal ---
        if (L.gateOpen && Math.abs(px + PW / 2 - L.exitX) < 34) completeLevel();

        // --- camera ---
        double wantCam = Math.max(0, Math.min(L.width - W, px + PW / 2 - W * 0.45));
        camX += (wantCam - camX) * 0.12;
    }

    boolean nearSpike() {
        for (double[] sp : level.spikes) if (px + PW > sp[0] - 70 && px < sp[0] + sp[1] + 70) return true;
        return false;
    }

    void hurt(String msg) {
        hearts--;
        invuln = 100; shake = 10;
        vy = -9; vx = facingRight ? -6 : 6; onGround = false;
        burst(px + PW / 2, py + PH / 2, 14, new Color(255, 80, 100), 4);
        if (hearts <= 0) { state = State.GAME_OVER; return; }
        say(msg + " -1 heart", 100);
    }

    boolean nearTerminal() { return Math.abs(px + PW / 2 - level.termX) < 75; }

    void tryInteract() {
        if (nearTerminal() && !level.solved) openQuiz();
        else if (nearTerminal()) say("Already solved! Head for the portal.", 90);
    }

    void completeLevel() {
        state = State.LEVEL_DONE;
        doneTimer = 0;
        score += 200;
        if (hearts < MAX_HEARTS) hearts++;
        burst(level.exitX, GROUND_Y - 70, 50, new Color(120, 255, 200), 6);
    }

    void updateLevelDone() {
        doneTimer++;
        camX += (Math.max(0, Math.min(level.width - W, level.exitX - W * 0.55)) - camX) * 0.08;
        if (doneTimer > 110) {
            if (levelIdx + 1 >= LEVELS) { state = State.VICTORY; fade = 0; parts.clear(); }
            else { startLevel(levelIdx + 1); state = State.PLAY; }
        }
    }

    // ---------- quiz ----------
    void openQuiz() {
        curQ = deck.get(deckPos++ % deck.size());
        List<Integer> o = new ArrayList<>(Arrays.asList(0, 1, 2, 3));
        Collections.shuffle(o, rnd);
        for (int i = 0; i < 4; i++) order[i] = o.get(i);
        chosen = -1; revealed = false; hoverOpt = -1; quizNo++;
        state = State.QUIZ;
    }

    void answer(int k) {
        if (revealed || k < 0 || k > 3) return;
        chosen = k; revealed = true;
        lastCorrect = order[k] == curQ.ans;
        if (lastCorrect) {
            streak++; solved++;
            lastGain = 100 + Math.min(streak - 1, 4) * 25;
            score += lastGain;
        } else {
            streak = 0; wrong++; hearts--;
        }
    }

    void closeQuiz() {
        if (lastCorrect) {
            level.solved = true; level.gateOpen = true;
            state = State.PLAY;
            burst(level.gateX + 13, GROUND_Y - 120, 40, new Color(80, 255, 160), 5);
            say("Gate unlocked! Run to the portal!", 200);
        } else if (hearts <= 0) {
            state = State.GAME_OVER;
        } else {
            state = State.PLAY;
            say("Not quite - press E at the terminal to try another question", 180);
        }
    }

    int correctDisplayIndex() {
        for (int k = 0; k < 4; k++) if (order[k] == curQ.ans) return k;
        return 0;
    }

    // ============================================================
    //  Input
    // ============================================================
    @Override public void keyPressed(KeyEvent e) {
        int k = e.getKeyCode();
        boolean first = down.add(k);                       // false when it is an auto-repeat
        if (!first) return;
        switch (state) {
            case MENU:
                if (k == KeyEvent.VK_LEFT || k == KeyEvent.VK_A) menuSel = (menuSel + langs.length - 1) % langs.length;
                else if (k == KeyEvent.VK_RIGHT || k == KeyEvent.VK_D) menuSel = (menuSel + 1) % langs.length;
                else if (k >= KeyEvent.VK_1 && k < KeyEvent.VK_1 + langs.length) menuSel = k - KeyEvent.VK_1;
                else if (k == KeyEvent.VK_ENTER || k == KeyEvent.VK_SPACE) startGame(langs[menuSel]);
                break;
            case PLAY:
                if (k == KeyEvent.VK_SPACE || k == KeyEvent.VK_W || k == KeyEvent.VK_UP) jumpBuffer = 8;
                else if (k == KeyEvent.VK_E || k == KeyEvent.VK_ENTER) tryInteract();
                else if (k == KeyEvent.VK_ESCAPE) toMenu();
                break;
            case QUIZ:
                if (!revealed) {
                    if (k >= KeyEvent.VK_1 && k <= KeyEvent.VK_4) answer(k - KeyEvent.VK_1);
                    else if (k >= KeyEvent.VK_A && k <= KeyEvent.VK_D) answer(k - KeyEvent.VK_A);
                } else if (k == KeyEvent.VK_ENTER || k == KeyEvent.VK_SPACE || k == KeyEvent.VK_E) {
                    closeQuiz();
                }
                break;
            case GAME_OVER:
            case VICTORY:
                if (k == KeyEvent.VK_ENTER || k == KeyEvent.VK_SPACE) startGame(lang);
                else if (k == KeyEvent.VK_M || k == KeyEvent.VK_ESCAPE) toMenu();
                break;
            default:
                break;
        }
    }

    @Override public void keyReleased(KeyEvent e) { down.remove(e.getKeyCode()); }
    @Override public void keyTyped(KeyEvent e) { }

    @Override public void mouseMoved(MouseEvent e) {
        Point p = e.getPoint();
        if (state == State.MENU) {
            for (int i = 0; i < langs.length; i++) if (menuCard(i).contains(p)) menuSel = i;
        } else if (state == State.QUIZ && !revealed) {
            hoverOpt = -1;
            for (int i = 0; i < 4; i++) if (optRects[i].contains(p)) hoverOpt = i;
        }
    }
    @Override public void mouseDragged(MouseEvent e) { mouseMoved(e); }

    @Override public void mousePressed(MouseEvent e) {
        requestFocusInWindow();
        Point p = e.getPoint();
        switch (state) {
            case MENU:
                for (int i = 0; i < langs.length; i++) if (menuCard(i).contains(p)) { menuSel = i; startGame(langs[i]); }
                break;
            case QUIZ:
                if (!revealed) { for (int i = 0; i < 4; i++) if (optRects[i].contains(p)) answer(i); }
                else closeQuiz();
                break;
            case GAME_OVER:
            case VICTORY:
                startGame(lang);
                break;
            default:
                break;
        }
    }
    @Override public void mouseClicked(MouseEvent e) { }
    @Override public void mouseReleased(MouseEvent e) { }
    @Override public void mouseEntered(MouseEvent e) { }
    @Override public void mouseExited(MouseEvent e) { }

    // ============================================================
    //  Rendering
    // ============================================================
    @Override protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        if (state == State.MENU) {
            drawMenu(g);
        } else {
            drawWorld(g);
            drawHud(g);
            if (state == State.QUIZ) drawQuiz(g);
            if (state == State.LEVEL_DONE) drawLevelDone(g);
            if (state == State.GAME_OVER) drawEndScreen(g, false);
            if (state == State.VICTORY) drawEndScreen(g, true);
            float f = state == State.LEVEL_DONE ? Math.max(0f, Math.min(1f, (doneTimer - 80) / 30f)) : fade;
            if (f > 0) { g.setColor(new Color(0, 0, 0, (int) (255 * f))); g.fillRect(0, 0, W, H); }
        }
        g.dispose();
        Toolkit.getDefaultToolkit().sync();
    }

    // ---------- small text helpers ----------
    void text(Graphics2D g, String s, int x, int y, Font f, Color c) { g.setFont(f); g.setColor(c); g.drawString(s, x, y); }
    void textC(Graphics2D g, String s, int cx, int y, Font f, Color c) {
        g.setFont(f); g.setColor(c); g.drawString(s, cx - g.getFontMetrics().stringWidth(s) / 2, y);
    }
    void textR(Graphics2D g, String s, int rx, int y, Font f, Color c) {
        g.setFont(f); g.setColor(c); g.drawString(s, rx - g.getFontMetrics().stringWidth(s), y);
    }
    static Color alpha(Color c, int a) { return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, a))); }
    static Color readable(Color c) {
        return (0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue()) > 150 ? new Color(0x14162B) : Color.WHITE;
    }
    static List<String> wrap(String text, FontMetrics fm, int maxW) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String t = line.length() == 0 ? word : line + " " + word;
            if (fm.stringWidth(t) > maxW && line.length() > 0) { out.add(line.toString()); line = new StringBuilder(word); }
            else line = new StringBuilder(t);
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    void drawHeart(Graphics2D g, double x, double y, double s, Color fill, boolean outlineOnly) {
        Path2D.Double p = new Path2D.Double();
        p.moveTo(x, y + s * 0.9);
        p.curveTo(x - s * 1.25, y + s * 0.05, x - s * 0.65, y - s * 0.85, x, y - s * 0.2);
        p.curveTo(x + s * 0.65, y - s * 0.85, x + s * 1.25, y + s * 0.05, x, y + s * 0.9);
        p.closePath();
        if (outlineOnly) { g.setColor(fill); g.setStroke(new BasicStroke(2f)); g.draw(p); }
        else { g.setColor(fill); g.fill(p); g.setColor(new Color(255, 255, 255, 120)); g.fill(new Ellipse2D.Double(x - s * 0.7, y - s * 0.35, s * 0.35, s * 0.25)); }
    }

    // ---------- menu ----------
    Rectangle menuCard(int i) { return new Rectangle(42 + i * 178, i == menuSel ? 240 : 250, 164, i == menuSel ? 260 : 240); }

    void drawMenu(Graphics2D g) {
        g.setPaint(new GradientPaint(0, 0, new Color(0x0B1033), 0, H, new Color(0x3A1C71)));
        g.fillRect(0, 0, W, H);
        String[] syms = {"{ }", "</>", "=>", "();", "[]", "&&", "!=", "++", "SELECT", "def", "int", "0x1F", "for", "null", "#include"};
        for (int k = 0; k < 26; k++) {
            double x = ((k * 137 + tick * (0.25 + (k % 5) * 0.1)) % (W + 160)) - 80;
            double y = (k * 83) % (H - 40) + Math.sin(tick * 0.02 + k) * 10 + 20;
            g.setFont(new Font("Monospaced", Font.BOLD, 18 + (k % 4) * 6));
            g.setColor(new Color(255, 255, 255, 28 + (k % 3) * 10));
            g.drawString(syms[k % syms.length], (int) x, (int) y);
        }
        // title
        g.setFont(F_TITLE);
        FontMetrics fm = g.getFontMetrics();
        int w1 = fm.stringWidth("CODE "), w2 = fm.stringWidth("QUEST");
        int tx = (W - w1 - w2) / 2;
        g.setColor(new Color(0, 0, 0, 90)); g.drawString("CODE ", tx + 4, 124); g.drawString("QUEST", tx + w1 + 4, 124);
        g.setColor(Color.WHITE); g.drawString("CODE ", tx, 120);
        g.setColor(new Color(0xFFD166)); g.drawString("QUEST", tx + w1, 120);
        textC(g, "Learn to code by playing. Pick your language!", W / 2, 165, F_H1, new Color(255, 255, 255, 210));
        textC(g, "Run, jump, and answer coding trivia to unlock each level.", W / 2, 195, F_TXT, new Color(255, 255, 255, 150));

        for (int i = 0; i < langs.length; i++) {
            Lang l = langs[i];
            Rectangle r = menuCard(i);
            boolean sel = i == menuSel;
            if (sel) { g.setColor(alpha(l.color, 70)); g.fillRoundRect(r.x - 8, r.y - 8, r.width + 16, r.height + 16, 34, 34); }
            g.setColor(new Color(0x1B1E3F)); g.fillRoundRect(r.x, r.y, r.width, r.height, 26, 26);
            g.setColor(l.color); g.fillRoundRect(r.x, r.y, r.width, 112, 26, 26); g.fillRect(r.x, r.y + 60, r.width, 52);
            textC(g, l.tag, r.x + r.width / 2, r.y + 76, F_BIG, readable(l.color));
            textC(g, l.name, r.x + r.width / 2, r.y + 152, F_H1, Color.WHITE);
            textC(g, l.qs.size() + " questions", r.x + r.width / 2, r.y + 178, F_SMALL, new Color(255, 255, 255, 150));
            textC(g, "[" + (i + 1) + "]", r.x + r.width / 2, r.y + 206, F_SMALL, alpha(l.color, 230));
            if (sel) textC(g, "ENTER to start", r.x + r.width / 2, r.y + 236, F_SMALL, Color.WHITE);
            g.setStroke(new BasicStroke(sel ? 4f : 2f));
            g.setColor(sel ? Color.WHITE : alpha(l.color, 120));
            g.drawRoundRect(r.x, r.y, r.width, r.height, 26, 26);
        }
        textC(g, "A/D or arrows: move     W / Up / Space: jump     E: use terminal     1-4: answer     Esc: menu",
              W / 2, 548, F_SMALL, new Color(255, 255, 255, 170));
        textC(g, "Click a card or press ENTER to begin", W / 2, 574, F_H2, new Color(0xFFD166));
    }

    // ---------- world ----------
    void drawSky(Graphics2D g) {
        Color[] pal = SKY[Math.min(levelIdx, SKY.length - 1)];
        g.setPaint(new GradientPaint(0, 0, pal[0], 0, H, pal[1]));
        g.fillRect(0, 0, W, H);
        if (levelIdx >= 3) {
            int a = Math.min(255, (levelIdx - 2) * 80);
            for (int k = 0; k < stars.length; k++) {
                double sx = (((stars[k][0] - camX * 0.04) % W) + W) % W;
                double tw = 0.5 + 0.5 * Math.sin(tick * 0.05 + k);
                g.setColor(new Color(255, 255, 255, (int) (a * tw)));
                g.fillOval((int) sx, (int) stars[k][1], 2, 2);
            }
        }
        // sun / moon
        double cxs = 780 - levelIdx * 8, cys = 110 + levelIdx * 14;
        g.setColor(levelIdx >= 4 ? new Color(235, 235, 255, 230) : new Color(255, 240, 160, 230));
        g.fill(new Ellipse2D.Double(cxs - 34, cys - 34, 68, 68));
        if (levelIdx <= 3) {
            for (double[] c : clouds) {
                double span = W + 300;
                double x = (((c[0] - camX * 0.25) % span) + span) % span - 150;
                g.setColor(new Color(255, 255, 255, 170));
                double s = c[2];
                g.fill(new Ellipse2D.Double(x, c[1], 90 * s, 30 * s));
                g.fill(new Ellipse2D.Double(x + 20 * s, c[1] - 14 * s, 50 * s, 34 * s));
                g.fill(new Ellipse2D.Double(x + 44 * s, c[1] - 6 * s, 56 * s, 30 * s));
            }
        }
        hills(g, 0.12, 330, 60, 0.004, new Color(0, 0, 0, 35));
        hills(g, 0.28, 385, 48, 0.006, new Color(0, 0, 0, 60));
    }

    void hills(Graphics2D g, double par, double base, double amp, double freq, Color c) {
        Path2D.Double p = new Path2D.Double();
        p.moveTo(0, H);
        for (int x = 0; x <= W; x += 12) {
            double wx = x + camX * par;
            p.lineTo(x, base + Math.sin(wx * freq) * amp + Math.sin(wx * freq * 2.3 + 1) * amp * 0.4);
        }
        p.lineTo(W, H); p.closePath();
        g.setColor(c); g.fill(p);
    }

    void drawWorld(Graphics2D g) {
        drawSky(g);
        AffineTransform old = g.getTransform();
        int sx = shake > 0 ? rnd.nextInt(shake * 2 + 1) - shake : 0;
        int sy = shake > 0 ? rnd.nextInt(shake * 2 + 1) - shake : 0;
        g.translate(-Math.round(camX) + sx, sy);
        Level L = level;
        Color accent = lang.color;

        // bug pit (lava) along the bottom - visible only through the pits
        g.setPaint(new GradientPaint(0, H - 44, new Color(0xFF9F1C), 0, H, new Color(0xE63946)));
        g.fill(new Rectangle2D.Double(camX - 20, H - 44, W + 40, 60));
        g.setColor(new Color(255, 230, 150, 160));
        for (int k = 0; k < 30; k++) {
            double bx = Math.floor(camX / 40) * 40 + k * 40;
            g.fill(new Ellipse2D.Double(bx + Math.sin(tick * 0.05 + k) * 6, H - 46 + Math.cos(tick * 0.07 + k) * 3, 26, 8));
        }

        // ground
        for (Rectangle2D.Double s : L.ground) {
            if (s.x + s.width < camX - 30 || s.x > camX + W + 30) continue;
            g.setColor(new Color(0x2B2D42)); g.fill(s);
            g.setColor(new Color(255, 255, 255, 14));
            for (double yy = s.y + 26; yy < H + 60; yy += 26) g.drawLine((int) s.x, (int) yy, (int) (s.x + s.width), (int) yy);
            g.setColor(accent); g.fillRect((int) s.x, (int) s.y, (int) s.width, 9);
            g.setColor(new Color(255, 255, 255, 80)); g.fillRect((int) s.x, (int) s.y, (int) s.width, 3);
            g.setColor(new Color(0, 0, 0, 70)); g.fillRect((int) s.x, (int) s.y + 9, 3, (int) s.height);
            g.fillRect((int) (s.x + s.width - 3), (int) s.y + 9, 3, (int) s.height);
        }
        // floating platforms
        for (Rectangle2D.Double s : L.plats) {
            g.setColor(new Color(0, 0, 0, 40)); g.fill(new RoundRectangle2D.Double(s.x + 3, s.y + 5, s.width, s.height, 10, 10));
            g.setColor(new Color(0x3D405B)); g.fill(new RoundRectangle2D.Double(s.x, s.y, s.width, s.height, 10, 10));
            g.setColor(accent); g.fill(new RoundRectangle2D.Double(s.x, s.y, s.width, 6, 6, 6));
        }
        // spikes
        for (double[] sp : L.spikes) {
            for (double x = sp[0]; x < sp[0] + sp[1] - 1; x += 18) {
                Path2D.Double tri = new Path2D.Double();
                tri.moveTo(x, GROUND_Y); tri.lineTo(x + 9, GROUND_Y - 24); tri.lineTo(x + 18, GROUND_Y); tri.closePath();
                g.setColor(new Color(0xEF476F)); g.fill(tri);
                g.setColor(new Color(0x8D1B3D)); g.setStroke(new BasicStroke(1.5f)); g.draw(tri);
            }
        }
        // coins
        for (double[] c : L.coins) {
            if (c[2] != 0) continue;
            double w = Math.abs(Math.cos(tick * 0.08 + c[0] * 0.05)) * 9 + 2;
            double by = c[1] + Math.sin(tick * 0.07 + c[0]) * 3;
            g.setColor(new Color(0xFFB703)); g.fill(new Ellipse2D.Double(c[0] - w, by - 9, w * 2, 18));
            g.setColor(new Color(0xFFE066)); g.fill(new Ellipse2D.Double(c[0] - w * 0.55, by - 6, w * 1.1, 12));
        }

        drawGate(g);
        drawPortal(g);
        drawTerminal(g);
        drawPlayer(g);

        // particles
        for (P p : parts) {
            g.setColor(alpha(p.c, (int) (255.0 * p.life / p.max)));
            g.fill(new Ellipse2D.Double(p.x - p.size / 2, p.y - p.size / 2, p.size, p.size));
        }
        g.setTransform(old);
    }

    void drawTerminal(Graphics2D g) {
        Level L = level;
        double x = L.termX, y = GROUND_Y;
        Color c = L.solved ? new Color(0x4CE08A) : lang.color;
        g.setColor(new Color(0, 0, 0, 50)); g.fill(new Ellipse2D.Double(x - 44, y - 6, 88, 12));
        g.setColor(new Color(0x1B1C2B));
        g.fill(new RoundRectangle2D.Double(x - 34, y - 20, 68, 20, 8, 8));
        g.fill(new Rectangle2D.Double(x - 6, y - 38, 12, 20));
        RoundRectangle2D mon = new RoundRectangle2D.Double(x - 44, y - 100, 88, 64, 12, 12);
        g.setColor(new Color(0x0E0F1C)); g.fill(mon);
        double pulse = 0.5 + 0.5 * Math.sin(tick * 0.08);
        g.setColor(alpha(c, L.solved ? 50 : (int) (35 + 40 * pulse)));
        g.fill(new RoundRectangle2D.Double(x - 40, y - 96, 80, 56, 8, 8));
        g.setStroke(new BasicStroke(3f)); g.setColor(c); g.draw(mon);
        if (L.solved) {
            g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Double(x - 14, y - 66, x - 4, y - 54));
            g.draw(new Line2D.Double(x - 4, y - 54, x + 16, y - 80));
        } else {
            g.setFont(new Font("Monospaced", Font.BOLD, 26));
            String t = lang.tag;
            g.drawString(t, (int) (x - g.getFontMetrics().stringWidth(t) / 2.0), (int) (y - 58));
            if ((tick / 30) % 2 == 0) g.fillRect((int) (x - 16), (int) (y - 50), 32, 3);
        }
        if (!L.solved) {
            double bob = Math.sin(tick * 0.1) * 4;
            textC(g, "?", (int) x, (int) (y - 114 + bob), F_BIG, new Color(0xFFD166));
            if (nearTerminal() && state == State.PLAY) {
                g.setColor(new Color(0, 0, 0, 170));
                g.fill(new RoundRectangle2D.Double(x - 62, y - 196, 124, 34, 16, 16));
                textC(g, "Press  E", (int) x, (int) (y - 173), F_H2, Color.WHITE);
            }
        }
    }

    void drawGate(Graphics2D g) {
        Level L = level;
        double open = L.gateAnim, gx = L.gateX;
        if (open >= 1) return;
        double yTop = -30 - open * (GROUND_Y + 30), h = GROUND_Y + 30;
        Color base = open > 0 ? new Color(60, 220, 140) : new Color(255, 80, 110);
        g.setColor(alpha(base, 35)); g.fill(new Rectangle2D.Double(gx - 16, yTop, 58, h));
        g.setColor(new Color(0x23253D)); g.fill(new Rectangle2D.Double(gx, yTop, 26, h));
        g.setColor(alpha(base, 170));
        double shift = (tick * 0.7) % 26;
        for (double yy = yTop + shift; yy < yTop + h - 12; yy += 26) g.fill(new Rectangle2D.Double(gx + 4, yy, 18, 9));
        g.setColor(base); g.setStroke(new BasicStroke(3f));
        g.draw(new Line2D.Double(gx, yTop, gx, yTop + h)); g.draw(new Line2D.Double(gx + 26, yTop, gx + 26, yTop + h));
        if (open < 0.4) {
            int ly = (int) (GROUND_Y - 150 - open * GROUND_Y);
            g.setColor(new Color(0xFFD166)); g.setStroke(new BasicStroke(3.5f));
            g.drawArc((int) gx + 5, ly - 16, 16, 24, 0, 180);
            g.fill(new RoundRectangle2D.Double(gx + 1, ly, 24, 20, 6, 6));
            g.setColor(new Color(0x23253D)); g.fillOval((int) gx + 10, ly + 5, 6, 6);
            textC(g, "LOCKED", (int) gx + 13, ly - 26, F_SMALL, new Color(255, 120, 140));
        }
    }

    void drawPortal(Graphics2D g) {
        Level L = level;
        double cx = L.exitX, cy = GROUND_Y - 72;
        boolean on = L.gateOpen;
        Color c = on ? new Color(0x7DF9C8) : new Color(150, 150, 170);
        for (int k = 0; k < 4; k++) {
            double wob = Math.sin(tick * (on ? 0.12 : 0.03) + k) * (on ? 4 : 1);
            double w = 56 - k * 12 + wob, hh = 128 - k * 22 + wob;
            g.setStroke(new BasicStroke(5f - k));
            g.setColor(alpha(c, on ? 230 - k * 40 : 90 - k * 15));
            g.draw(new Ellipse2D.Double(cx - w / 2, cy - hh / 2, w, hh));
        }
        if (on) {
            g.setColor(alpha(c, 50)); g.fill(new Ellipse2D.Double(cx - 28, cy - 64, 56, 128));
            textC(g, "NEXT LEVEL", (int) cx, (int) cy - 82, F_H2, Color.WHITE);
        }
    }

    void drawPlayer(Graphics2D g) {
        if (invuln > 0 && (tick / 4) % 2 == 0) return;
        double sy = 1 - squash * 0.4 + (onGround ? 0 : Math.min(0.12, Math.abs(vy) * 0.01));
        double sx = 2 - sy;
        double w = PW * sx, h = PH * sy;
        double x = px + PW / 2 - w / 2, y = py + PH - h;
        Color body = new Color(0xF1FAEE);
        double stride = onGround && Math.abs(vx) > 0.6 ? Math.sin(tick * 0.45) * 5 : 0;
        g.setColor(new Color(0, 0, 0, 45)); g.fill(new Ellipse2D.Double(px - 2, py + PH - 3, PW + 4, 7));
        g.setColor(lang.color.darker());
        g.fill(new RoundRectangle2D.Double(x + w * 0.12 + stride, y + h - 12, w * 0.32, 12, 5, 5));
        g.fill(new RoundRectangle2D.Double(x + w * 0.56 - stride, y + h - 12, w * 0.32, 12, 5, 5));
        g.setColor(body); g.fill(new RoundRectangle2D.Double(x, y + 4, w, h - 12, 14, 14));
        g.setColor(lang.color); g.fill(new Rectangle2D.Double(x, y + h - 26, w, 6));
        double vxs = x + w * (facingRight ? 0.34 : 0.04), vw = w * 0.62;
        g.setColor(new Color(0x1D3557)); g.fill(new RoundRectangle2D.Double(vxs, y + 11, vw, 13, 9, 9));
        g.setColor(new Color(0x7DF9FF));
        double ex = facingRight ? 0.45 : 0.1;
        g.fill(new Ellipse2D.Double(x + w * ex + w * 0.04, y + 14, 5, 7));
        g.fill(new Ellipse2D.Double(x + w * (ex + 0.25) + w * 0.04, y + 14, 5, 7));
        g.setColor(body.darker()); g.setStroke(new BasicStroke(2f));
        g.draw(new Line2D.Double(x + w / 2, y + 4, x + w / 2, y - 4));
        g.setColor(lang.color); g.fill(new Ellipse2D.Double(x + w / 2 - 3.5, y - 9, 7, 7));
    }

    // ---------- HUD & overlays ----------
    void drawHud(Graphics2D g) {
        // hearts
        g.setColor(new Color(0, 0, 0, 95)); g.fillRoundRect(10, 10, 244, 46, 18, 18);
        for (int i = 0; i < MAX_HEARTS; i++) {
            if (i < hearts) drawHeart(g, 34 + i * 43, 32, 11, new Color(0xFF4D6D), false);
            else drawHeart(g, 34 + i * 43, 32, 11, new Color(255, 255, 255, 80), true);
        }
        // level info
        g.setColor(new Color(0, 0, 0, 95)); g.fillRoundRect(W / 2 - 150, 10, 300, 46, 18, 18);
        textC(g, lang.name + "  -  Level " + (levelIdx + 1) + " / " + LEVELS, W / 2, 30, F_H2, Color.WHITE);
        for (int i = 0; i < LEVELS; i++) {
            boolean done = i < levelIdx || (i == levelIdx && (level.solved));
            g.setColor(done ? new Color(0x4CE08A) : (i == levelIdx ? lang.color : new Color(255, 255, 255, 70)));
            g.fillOval(W / 2 - 55 + i * 22, 38, 11, 11);
        }
        // score
        g.setColor(new Color(0, 0, 0, 95)); g.fillRoundRect(W - 250, 10, 240, 46, 18, 18);
        textR(g, "Score " + score, W - 26, 30, F_H2, new Color(0xFFD166));
        textR(g, "Streak x" + streak + "   Solved " + solved, W - 26, 49, F_SMALL, new Color(255, 255, 255, 190));
        // objective + toast
        if (state == State.PLAY) {
            String obj = level.solved ? "Gate unlocked - jump to the portal!" : "Goal: reach the terminal [E] and answer to unlock the gate";
            g.setFont(F_SMALL);
            int ow = g.getFontMetrics().stringWidth(obj) + 30;
            g.setColor(new Color(0, 0, 0, 80)); g.fillRoundRect(W / 2 - ow / 2, 64, ow, 26, 14, 14);
            textC(g, obj, W / 2, 82, F_SMALL, Color.WHITE);
            text(g, "A/D move  |  W/Space jump  |  E interact  |  Esc menu", 14, H - 12, F_SMALL, new Color(255, 255, 255, 150));
        }
        if (toastTimer > 0 && state == State.PLAY) {
            g.setFont(F_H2);
            int tw = g.getFontMetrics().stringWidth(toast) + 40;
            g.setColor(new Color(10, 12, 30, Math.min(220, toastTimer * 6)));
            g.fillRoundRect(W / 2 - tw / 2, H - 100, tw, 40, 20, 20);
            textC(g, toast, W / 2, H - 73, F_H2, new Color(255, 255, 255, Math.min(255, toastTimer * 8)));
        }
    }

    void drawQuiz(Graphics2D g) {
        g.setColor(new Color(5, 6, 20, 195)); g.fillRect(0, 0, W, H);
        int cx = 100, cy = 34, cw = W - 200, ch = 532;
        g.setColor(new Color(0x14162B)); g.fillRoundRect(cx, cy, cw, ch, 28, 28);
        g.setColor(lang.color); g.fillRoundRect(cx, cy, cw, 46, 28, 28); g.fillRect(cx, cy + 24, cw, 22);
        Color hc = readable(lang.color);
        text(g, ">_ TERMINAL  |  " + lang.name + "  |  Question " + quizNo, cx + 22, cy + 30, F_H2, hc);
        if (!revealed) textR(g, "Press 1-4 or click", cx + cw - 22, cy + 30, F_SMALL, hc);
        else if (lastCorrect) textR(g, "CORRECT!  +" + lastGain + "   [Enter]", cx + cw - 22, cy + 30, F_H2, hc);
        else textR(g, "WRONG  -1 heart   [Enter]", cx + cw - 22, cy + 30, F_H2, hc);
        g.setStroke(new BasicStroke(3f)); g.setColor(lang.color); g.drawRoundRect(cx, cy, cw, ch, 28, 28);

        // question
        g.setFont(F_H1);
        FontMetrics fm = g.getFontMetrics();
        List<String> lines = wrap(curQ.text, fm, cw - 70);
        int y = cy + 84;
        for (String s : lines) { text(g, s, cx + 35, y, F_H1, Color.WHITE); y += 29; }
        int contentBottom = y;
        // code snippet
        if (curQ.code != null) {
            String[] cl = curQ.code.split("\n");
            int bh = cl.length * 23 + 18;
            g.setColor(new Color(0x0A0B16)); g.fillRoundRect(cx + 35, y - 10, cw - 70, bh, 14, 14);
            g.setColor(alpha(lang.color, 180)); g.fillRoundRect(cx + 35, y - 10, 5, bh, 5, 5);
            int ly = y + 15;
            for (String s : cl) { text(g, s, cx + 56, ly, F_MONO, new Color(0xC3E88D)); ly += 23; }
            contentBottom = y - 10 + bh;
        }
        // options
        int oy = Math.min(cy + 262, Math.max(cy + 170, contentBottom + 22));
        int right = correctDisplayIndex();
        for (int k = 0; k < 4; k++) {
            Rectangle r = optRects[k];
            r.setBounds(cx + 35, oy + k * 52, cw - 70, 44);
            Color bg = new Color(0x23264A), border = new Color(255, 255, 255, 40);
            if (!revealed && k == hoverOpt) { bg = new Color(0x303466); border = lang.color; }
            if (revealed) {
                if (k == right) { bg = new Color(0x15693F); border = new Color(0x4CE08A); }
                else if (k == chosen) { bg = new Color(0x8F2236); border = new Color(0xFF4D6D); }
                else bg = new Color(0x1B1D38);
            }
            g.setColor(bg); g.fillRoundRect(r.x, r.y, r.width, r.height, 16, 16);
            g.setStroke(new BasicStroke(2f)); g.setColor(border); g.drawRoundRect(r.x, r.y, r.width, r.height, 16, 16);
            g.setColor(revealed && k != right && k != chosen ? new Color(255, 255, 255, 40) : lang.color);
            g.fillOval(r.x + 10, r.y + 8, 28, 28);
            textC(g, String.valueOf(k + 1), r.x + 24, r.y + 28, F_H2, readable(lang.color));
            text(g, curQ.opts[order[k]], r.x + 54, r.y + 28, F_TXT,
                 revealed && k != right && k != chosen ? new Color(255, 255, 255, 110) : Color.WHITE);
        }
        // explanation
        if (revealed) {
            g.setFont(F_WHY);
            List<String> ex = wrap((lastCorrect ? "Nice! " : "Answer: " + curQ.opts[curQ.ans] + ".  ") + curQ.why,
                                   g.getFontMetrics(), cw - 70);
            int ey = oy + 4 * 52 + 14;
            for (int i = 0; i < Math.min(2, ex.size()); i++) text(g, ex.get(i), cx + 35, ey + i * 20, F_WHY, new Color(0xFFD166));
        }
    }

    void drawLevelDone(Graphics2D g) {
        float t = Math.min(1f, doneTimer / 20f);
        g.setColor(new Color(0, 0, 0, (int) (110 * t)));
        g.fillRect(0, 0, W, H);
        int off = (int) ((1 - t) * 40);
        textC(g, "LEVEL " + (levelIdx + 1) + " COMPLETE!", W / 2 + 3, H / 2 - 20 + off + 3, F_TITLE, new Color(0, 0, 0, (int) (140 * t)));
        textC(g, "LEVEL " + (levelIdx + 1) + " COMPLETE!", W / 2, H / 2 - 20 + off, F_TITLE, new Color(255, 255, 255, (int) (255 * t)));
        textC(g, "+200 bonus     +1 heart", W / 2, H / 2 + 30 + off, F_H1, new Color(255, 209, 102, (int) (255 * t)));
    }

    void drawEndScreen(Graphics2D g, boolean win) {
        g.setColor(new Color(5, 6, 20, 190)); g.fillRect(0, 0, W, H);
        if (win) {
            textC(g, "YOU MASTERED " + lang.name.toUpperCase() + "!", W / 2, 190, F_BIG, new Color(0xFFD166));
            String rank = wrong == 0 ? "Principal Engineer (flawless!)" : wrong <= 2 ? "Senior Developer"
                        : wrong <= 5 ? "Developer" : "Rookie Coder - keep practicing!";
            textC(g, "Rank: " + rank, W / 2, 245, F_H1, Color.WHITE);
        } else {
            textC(g, "GAME OVER", W / 2, 190, F_TITLE, new Color(0xFF4D6D));
            textC(g, "Your code crashed... but every bug teaches something.", W / 2, 245, F_H2, Color.WHITE);
        }
        textC(g, "Score: " + score + "      Correct: " + solved + "      Wrong: " + wrong, W / 2, 310, F_H1, Color.WHITE);
        textC(g, "Reached level " + (levelIdx + 1) + " of " + LEVELS, W / 2, 345, F_TXT, new Color(255, 255, 255, 180));
        textC(g, "ENTER / click: play " + lang.name + " again", W / 2, 420, F_H2, new Color(0xFFD166));
        textC(g, "M or Esc: choose another language", W / 2, 452, F_H2, new Color(255, 255, 255, 200));
    }
}
