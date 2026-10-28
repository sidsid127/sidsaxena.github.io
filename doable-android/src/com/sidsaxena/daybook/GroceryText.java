package com.sidsaxena.daybook;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Quantities, aisle categories and the shareable list format for groceries. Pure Java. */
final class GroceryText {
    private GroceryText() {}

    /** Aisle order through a typical store. */
    static final String[] AISLES = {"Fruit & veg", "Bakery", "Dairy & eggs", "Meat & fish", "Frozen", "Pantry",
            "Snacks & sweets", "Drinks", "Household", "Personal care", "Baby & pets", "Other"};

    // English and German keywords (stems), matched against the start of each word.
    private static final String[][] KEYWORDS = {
            {"apple", "banana", "orange", "lemon", "lime", "grape", "berr", "tomato", "potato", "onion", "garlic", "carrot",
                    "salad", "lettuce", "cucumber", "pepper", "paprika", "avocado", "mushroom", "spinach", "broccoli", "zucchini",
                    "herb", "basil", "parsley", "ginger", "fruit", "veg", "apfel", "äpfel", "banane", "zitrone", "traube",
                    "tomate", "kartoffel", "zwiebel", "knoblauch", "karotte", "möhre", "gurke", "pilz", "champignon",
                    "spinat", "obst", "gemüse", "mango", "kiwi", "melon", "pear", "birne", "erdbeer", "strawberr", "kohl"},
            {"bread", "bun", "roll", "toast", "baguette", "croissant", "bagel", "brot", "brötchen", "semmel", "laugen",
                    "brezel", "pretzel", "cake", "kuchen"},
            {"milk", "cheese", "yog", "butter", "cream", "egg", "quark", "milch", "käse", "joghurt", "sahne", "eier",
                    "ei ", "skyr", "feta", "mozzarella", "parmesan", "gouda", "frischkäse", "schmand", "kefir", "margarine"},
            {"chicken", "beef", "pork", "mince", "steak", "sausage", "ham", "bacon", "salami", "fish", "salmon", "tuna",
                    "shrimp", "hähnchen", "huhn", "rind", "schwein", "hack", "wurst", "schinken", "speck", "fisch", "lachs",
                    "thunfisch", "garnele", "aufschnitt", "turkey", "pute"},
            {"frozen", "ice cream", "frozen pizza", "pizza", "tiefkühl", "eis", "fries", "pommes"},
            {"rice", "pasta", "noodle", "flour", "sugar", "salt", "oil", "vinegar", "sauce", "ketchup", "mustard", "mayo",
                    "cereal", "muesli", "müsli", "oat", "hafer", "honey", "honig", "jam", "marmelade", "coffee", "kaffee",
                    "tea", "tee", "spice", "gewürz", "canned", "dose", "bean", "bohne", "lentil", "linse", "reis", "nudel",
                    "mehl", "zucker", "salz", "öl", "essig", "soße", "senf", "stock", "brühe", "nut", "nuss", "spaghetti"},
            {"chocolate", "chips", "crisps", "cookie", "biscuit", "candy", "sweet", "snack", "schokolade", "keks",
                    "süß", "gummi", "popcorn", "cracker"},
            {"water", "juice", "beer", "wine", "soda", "cola", "coke", "lemonade", "wasser", "saft", "bier", "wein",
                    "sprudel", "limo", "drink", "getränk", "sekt", "prosecco"},
            {"toilet", "paper", "tissue", "detergent", "soap", "dish", "sponge", "trash", "bag", "foil", "battery",
                    "bulb", "clean", "spül", "wasch", "klopapier", "toilettenpapier", "küchenrolle", "taschentuch",
                    "müllbeutel", "folie", "batterie", "reiniger", "schwamm", "tücher"},
            {"sun cream", "hand cream", "face cream", "body lotion", "shampoo", "conditioner", "toothpaste", "toothbrush", "deo", "razor", "lotion", "cream ", "sunscreen",
                    "zahnpasta", "zahnbürste", "duschgel", "shower", "rasier", "sonnencreme", "pflaster", "plaster",
                    "medic", "tablet", "vitamin", "make-up", "makeup", "tampon", "binde", "pad"},
            {"diaper", "nappy", "windel", "baby", "cat", "dog", "katze", "hund", "pet", "futter"},
    };

    static String aisle(String name) {
        String n = " " + name.toLowerCase(Locale.ROOT) + " ";
        // Multi-word keywords first, so "sun cream" isn't taken for dairy and "ice cream" is frozen.
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < KEYWORDS.length; i++) {
                for (String k : KEYWORDS[i]) {
                    boolean multi = k.trim().contains(" ");
                    if (multi == (pass == 0) && n.contains(" " + k)) return AISLES[i];
                }
            }
        }
        return AISLES[AISLES.length - 1];
    }

    static int aisleIndex(String aisle) {
        for (int i = 0; i < AISLES.length; i++) if (AISLES[i].equals(aisle)) return i;
        return AISLES.length - 1;
    }

    // ---- quantities: "2 kg", "1 pack", "10 pcs" (canonical: number with ".", one space, unit from UNITS)

    static final String[] UNITS = {"pcs", "pack", "kg", "g", "l", "ml", "bottle", "can", "bag", "box", "dozen"};
    private static final String[][] UNIT_WORDS = {
            {"x", ""}, {"×", ""}, {"pc", "pcs"}, {"pcs", "pcs"}, {"piece", "pcs"}, {"pieces", "pcs"}, {"stk", "pcs"}, {"st", "pcs"}, {"stück", "pcs"},
            {"pack", "pack"}, {"packs", "pack"}, {"packet", "pack"}, {"packets", "pack"}, {"pkg", "pack"}, {"pck", "pack"}, {"packung", "pack"},
            {"packungen", "pack"}, {"kg", "kg"}, {"kilo", "kg"}, {"kilos", "kg"}, {"kilogram", "kg"}, {"kilograms", "kg"}, {"g", "g"}, {"gr", "g"},
            {"gram", "g"}, {"grams", "g"}, {"gramm", "g"}, {"l", "l"}, {"ltr", "l"}, {"liter", "l"}, {"litre", "l"}, {"liters", "l"}, {"litres", "l"},
            {"ml", "ml"}, {"bottle", "bottle"}, {"bottles", "bottle"}, {"flasche", "bottle"}, {"flaschen", "bottle"}, {"can", "can"}, {"cans", "can"},
            {"dose", "can"}, {"dosen", "can"}, {"bag", "bag"}, {"bags", "bag"}, {"tüte", "bag"}, {"box", "box"}, {"boxes", "box"}, {"dozen", "dozen"},
    };
    private static final String NUM = "(\\d+(?:[.,]\\d+)?)";
    private static final String UNIT_RE;

    static {
        List<String> words = new ArrayList<String>();
        for (String[] w : UNIT_WORDS) words.add(w[0]);
        java.util.Collections.sort(words, new java.util.Comparator<String>() {
            public int compare(String a, String b) { return b.length() - a.length(); }
        });
        StringBuilder sb = new StringBuilder();
        for (String w : words) sb.append(sb.length() == 0 ? "" : "|").append(Pattern.quote(w));
        UNIT_RE = sb.toString();
    }

    /** Unit word → one of UNITS ("" for "x"), or null if it isn't a unit. */
    static String normalizeUnit(String w) {
        String k = (w == null ? "" : w.trim().toLowerCase(Locale.ROOT)).replaceFirst("\\.$", "");
        for (String[] u : UNIT_WORDS) if (u[0].equals(k)) return u[1];
        return null;
    }

    private static String num(double n) {
        double r = Math.round(n * 1000) / 1000.0;
        return r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r);
    }

    static String formatQty(double n, String unit) {
        return n > 0 ? num(n) + (unit.isEmpty() ? "" : " " + unit) : "";
    }

    /** "1,5 kg" → {1.5, "kg"}; "" → {0, ""}. Unknown words stay as the unit text. */
    static Object[] parseQty(String q) {
        Matcher m = Pattern.compile("^\\s*(\\d+(?:[.,]\\d+)?)\\s*(.*?)\\s*$").matcher(q == null ? "" : q);
        if (!m.matches()) return new Object[]{0.0, ""};
        String u = normalizeUnit(m.group(2));
        return new Object[]{Double.parseDouble(m.group(1).replace(',', '.')), u == null ? m.group(2) : u};
    }

    static double amount(String q) {
        return (Double) parseQty(q)[0];
    }

    static String unit(String q) {
        return (String) parseQty(q)[1];
    }

    private static String qtyOf(String number, String unitWord) {
        return formatQty(Double.parseDouble(number.replace(',', '.')), unitWord == null ? "" : normalizeUnit(unitWord));
    }

    /** [quantity, name]: "2 milk", "500g flour", "1,5 kg potatoes", "milk x2", "Milk × 2 l", "2x eggs", "eggs (10)". */
    static String[] splitQuantity(String input) {
        String s = input.trim();
        Matcher m = Pattern.compile("^" + NUM + "\\s*(" + UNIT_RE + ")?\\.?\\s+(.+)$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(s);
        if (m.matches()) return new String[]{qtyOf(m.group(1), m.group(2)), m.group(3).trim()};
        m = Pattern.compile("^(\\d+)\\s*[x×](\\S.*)$", Pattern.CASE_INSENSITIVE).matcher(s);
        if (m.matches()) return new String[]{m.group(1), m.group(2).trim()};
        m = Pattern.compile("^(.+?)\\s+[x×]?\\s*" + NUM + "\\s*(" + UNIT_RE + ")?\\.?$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(s);
        if (m.matches() && m.group(1).matches(".*\\D.*")) return new String[]{qtyOf(m.group(2), m.group(3)), m.group(1).replaceFirst("\\s*[x×]$", "").trim()};
        m = Pattern.compile("^(.+?)\\s*\\(" + NUM + "\\s*(" + UNIT_RE + ")?\\)$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(s);
        if (m.matches()) return new String[]{qtyOf(m.group(2), m.group(3)), m.group(1).trim()};
        return new String[]{"", s};
    }

    private static boolean isQty(String t) {
        return Pattern.compile("^" + NUM + "\\s*(" + UNIT_RE + ")?\\.?$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(t.trim()).matches();
    }

    // Usual unit (and amount) for common products, English and German stems; anything else is counted in pieces.
    private static final Object[][] UNIT_GUESS = {
            {"l", 1, new String[]{"milk", "milch", "juice", "saft", "water", "wasser", "oil", "öl", "vinegar", "essig", "buttermilk"}},
            {"kg", 1, new String[]{"potato", "kartoffel", "apple", "apfel", "äpfel", "onion", "zwiebel", "flour", "mehl", "sugar", "zucker", "rice",
                    "reis", "carrot", "karotte", "möhre", "tomato", "tomate", "banana", "banane", "orange", "grape", "traube", "pear", "birne", "mince",
                    "hack", "chicken", "hähnchen", "beef", "rind", "pork", "schwein", "meat", "fleisch"}},
            {"pack", 1, new String[]{"pasta", "nudel", "spaghetti", "butter", "cheese", "käse", "coffee", "kaffee", "tea", "tee", "cereal", "müsli",
                    "muesli", "toast", "chips", "cookie", "keks", "toilet", "klopapier", "tissue", "taschentuch", "ham", "schinken", "salami", "quark",
                    "oat", "hafer"}},
            {"bottle", 1, new String[]{"wine", "wein", "beer", "bier", "shampoo", "ketchup", "cola", "coke", "soda", "sprudel", "limo", "sekt", "prosecco"}},
            {"can", 1, new String[]{"tuna", "thunfisch", "beans", "bohnen", "corn", "mais"}},
            {"pcs", 10, new String[]{"egg", "eier"}},
    };

    /** "1 l" for milk, "1 kg" for potatoes, "10 pcs" for eggs, "1 pcs" otherwise. */
    static String defaultQty(String name) {
        String n = " " + (name == null ? "" : name.toLowerCase(Locale.ROOT));
        for (Object[] g : UNIT_GUESS) {
            for (String w : (String[]) g[2]) if (n.contains(" " + w)) return formatQty((Integer) g[1], (String) g[0]);
        }
        return "1 pcs";
    }

    private static double step(String unit) {
        return unit.equals("kg") || unit.equals("l") ? 0.5 : unit.equals("g") ? 100 : unit.equals("ml") ? 250 : 1;
    }

    /** One step up (dir 1) or down (-1): pieces by 1, kg / l by 0.5, g by 100, ml by 250; never below one step. */
    static String stepQty(String q, int dir) {
        double n = amount(q);
        String unit = unit(q);
        double st = step(unit);
        double v = Math.max(st, Math.round((n + dir * st) / st) * st);
        return formatQty(v, unit);
    }

    /** Same amount in another unit: "2 kg" → "2 pack". */
    static String withUnit(String q, String unit) {
        double n = amount(q);
        return formatQty(n > 0 ? n : 1, unit);
    }

    /** "1.29 € / kg" for a price per one unit, "3.00 € for 2 kg" otherwise. */
    static String unitPrice(String money, String qty) {
        double n = amount(qty);
        String unit = unit(qty);
        if (n == 1 && !unit.isEmpty()) return money + " / " + (unit.equals("pcs") ? "piece" : unit);
        return qty == null || qty.isEmpty() ? money : money + " for " + qty;
    }

    /** One item as shared / read back: name, quantity, shop name ("" = any shop). */
    static final class Line {
        String name, qty, shop;

        Line(String name, String qty, String shop) {
            this.name = name;
            this.qty = qty;
            this.shop = shop;
        }
    }

    /** Text for WhatsApp etc. Shop names on their own line, items as "• Milk × 2 l". */
    static String format(String listName, List<Line> lines) {
        StringBuilder sb = new StringBuilder("🛒 ").append(listName).append('\n');
        String shop = null;
        for (Line l : lines) {
            if (!l.shop.equals(shop)) {
                sb.append('\n').append(l.shop.isEmpty() ? "Any shop" : l.shop).append('\n');
                shop = l.shop;
            }
            sb.append("• ").append(l.name).append(l.qty.isEmpty() ? "" : " × " + l.qty).append('\n');
        }
        sb.append("\n(Edit and share back to Doable to update the list)");
        return sb.toString();
    }

    /** Reads a list shared back (or any pasted list). Lines matching a known shop name start that shop's section. */
    static List<Line> parse(String text, List<String> shopNames) {
        List<Line> out = new ArrayList<Line>();
        String shop = "";
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("🛒") || line.startsWith("(")) continue;
            boolean bullet = line.matches("^([•\\-*·☐☑✓✔]|\\[[ xX]?\\])\\s*.*");
            String clean = line.replaceFirst("^([•\\-*·☐☑✓✔]|\\[[ xX]?\\])\\s*", "").trim();
            if (!bullet) {
                String match = null;
                for (String s : shopNames) if (s.equalsIgnoreCase(clean)) match = s;
                if (match != null || clean.equalsIgnoreCase("Any shop")) {
                    shop = match == null ? "" : match;
                    continue;
                }
            }
            if (clean.isEmpty()) continue;
            String qty = "", name = clean;
            // "Milk × 2 l" (now) or "2 × Milk" (older lists): the side that is an amount is the quantity.
            Matcher m = Pattern.compile("^(.+?)\\s*×\\s*(.+)$").matcher(clean);
            if (m.matches() && isQty(m.group(2))) {
                String[] q = splitQuantity("x " + m.group(2));
                qty = q[0].isEmpty() ? m.group(2).trim() : q[0];
                name = m.group(1).trim();
            } else if (m.matches() && isQty(m.group(1))) {
                qty = m.group(1).trim();
                name = m.group(2).trim();
            } else {
                String[] q = splitQuantity(clean);
                qty = q[0];
                name = q[1];
            }
            out.add(new Line(name, qty, shop));
        }
        return out;
    }
}
