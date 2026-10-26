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

    /** [quantity, name]: "2 milk", "500g flour", "1,5 kg potatoes", "milk x2", "2x eggs", "eggs (10)". */
    static String[] splitQuantity(String input) {
        String s = input.trim();
        Matcher m = Pattern.compile("^(\\d+(?:[.,]\\d+)?)\\s*(x|×|kg|g|l|ml|pack|packs|pcs|stk|st\\.?|dozen|bottles?|cans?|bags?)?\\s+(.+)$",
                Pattern.CASE_INSENSITIVE).matcher(s);
        if (m.matches()) return new String[]{qty(m.group(1), m.group(2)), m.group(3).trim()};
        m = Pattern.compile("^(\\d+)\\s*[x×](\\S.*)$", Pattern.CASE_INSENSITIVE).matcher(s);
        if (m.matches()) return new String[]{m.group(1), m.group(2).trim()};
        m = Pattern.compile("^(.+?)\\s+[x×]\\s*(\\d+)$|^(.+?)\\s*\\((\\d+(?:[.,]\\d+)?\\s*\\w*)\\)$", Pattern.CASE_INSENSITIVE).matcher(s);
        if (m.matches()) {
            if (m.group(1) != null) return new String[]{m.group(2), m.group(1).trim()};
            return new String[]{m.group(4).trim(), m.group(3).trim()};
        }
        return new String[]{"", s};
    }

    private static String qty(String number, String unit) {
        if (unit == null || unit.equalsIgnoreCase("x") || unit.equals("×")) return number;
        return number + " " + unit.toLowerCase(Locale.ROOT);
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

    /** Text for WhatsApp etc. Shop names on their own line, items as "• 2 × Milk". */
    static String format(String listName, List<Line> lines) {
        StringBuilder sb = new StringBuilder("🛒 ").append(listName).append('\n');
        String shop = null;
        for (Line l : lines) {
            if (!l.shop.equals(shop)) {
                sb.append('\n').append(l.shop.isEmpty() ? "Any shop" : l.shop).append('\n');
                shop = l.shop;
            }
            sb.append("• ").append(l.qty.isEmpty() ? "" : l.qty + " × ").append(l.name).append('\n');
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
            Matcher m = Pattern.compile("^(.+?)\\s*×\\s*(.+)$").matcher(clean);
            if (m.matches()) {
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
