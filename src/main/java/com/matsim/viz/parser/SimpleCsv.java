package com.matsim.viz.parser;

import java.util.ArrayList;
import java.util.List;

public final class SimpleCsv {
    private SimpleCsv() {
    }

    public static List<String> parseLine(String line) {
        List<String> columns = new ArrayList<>();
        if (line == null) {
            return columns;
        }

        StringBuilder token = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    token.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ';' && !inQuotes) {
                columns.add(token.toString().trim());
                token.setLength(0);
            } else {
                token.append(c);
            }
        }

        columns.add(token.toString().trim());
        return columns;
    }
}
