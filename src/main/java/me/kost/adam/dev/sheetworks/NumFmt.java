/*
 * SheetWorks - a small spreadsheet application
 * Copyright (C) 2026 Adam Kost
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 3 as published by the
 * Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package me.kost.adam.dev.sheetworks;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Converts values to display text using a (simplified) Excel number format code. */
public final class NumFmt {
    private NumFmt() {}

    public static String format(Object v, String code) {
        if (v == null) return "";
        if (v instanceof Boolean) return ((Boolean) v) ? "TRUE" : "FALSE";
        if (v instanceof XlErr) return ((XlErr) v).code;
        if (!(v instanceof Double)) return v.toString();
        double d = (Double) v;
        if (code == null || code.isEmpty() || code.equalsIgnoreCase("General")) return general(d);
        try {
            String c = code.split(";", -1)[0];
            if (isDate(c)) return date(d, c);
            return number(d, c);
        } catch (RuntimeException e) {
            return general(d);
        }
    }

    /** The "General" format: up to 10 significant digits. */
    public static String general(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
        double a = Math.abs(d);
        if (a >= 1e15 || a < 1e-9) return Double.toString(d);
        return new BigDecimal(d).round(new MathContext(10)).stripTrailingZeros().toPlainString();
    }

    /** Shortest exact text for storing a number in a file. */
    public static String plain(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
        return Double.toString(d);
    }

    static boolean isDate(String code) {
        String s = code.replaceAll("\"[^\"]*\"", "").replaceAll("\\[[^\\]]*\\]", "").replaceAll("\\\\.", "");
        return s.matches(".*[ydhsYDHS].*") && !s.toUpperCase().contains("E+") && !s.toUpperCase().contains("E-");
    }

    private static String number(double d, String code) {
        String s = code.replaceAll("\\[\\$([^\\]-]*)[^\\]]*\\]", "$1").replaceAll("\\[[^\\]]*\\]", "");
        int first = -1, last = -1;
        for (int i = 0; i < s.length(); i++) {
            if ("0#?".indexOf(s.charAt(i)) >= 0) { if (first < 0) first = i; last = i; }
        }
        if (first < 0) return general(d);
        String body = s.substring(first, last + 1);
        if (body.toUpperCase().contains("E")) return general(d);
        String prefix = clean(s.substring(0, first)), suffix = clean(s.substring(last + 1));
        if (s.indexOf('%') >= 0) d *= 100;
        boolean thousands = body.indexOf(',') >= 0;
        int dot = body.indexOf('.');
        String ip = dot < 0 ? body : body.substring(0, dot);
        String fp = dot < 0 ? "" : body.substring(dot + 1);
        int minInt = 0;
        for (char ch : ip.toCharArray()) if (ch == '0') minInt++;
        StringBuilder pat = new StringBuilder();
        if (thousands) pat.append("#,##0"); else for (int i = 0; i < Math.max(1, minInt); i++) pat.append('0');
        StringBuilder dec = new StringBuilder();
        for (char ch : fp.toCharArray()) if (ch == '0') dec.append('0'); else if (ch == '#' || ch == '?') dec.append('#');
        if (dec.length() > 0) pat.append('.').append(dec);
        DecimalFormat df = new DecimalFormat(pat.toString(), DecimalFormatSymbols.getInstance(Locale.US));
        df.setRoundingMode(RoundingMode.HALF_UP);
        String sign = d < 0 ? "-" : "";
        String out = df.format(Math.abs(d));
        if (out.matches("[0.,]*") && out.replaceAll("[^1-9]", "").isEmpty()) sign = "";
        return sign + prefix + out + suffix;
    }

    private static String clean(String s) {
        return s.replaceAll("_.", "").replaceAll("\\*.", "").replace("\"", "").replace("\\", "");
    }

    private static String date(double d, String code) {
        long days = (long) Math.floor(d);
        long secs = Math.round((d - days) * 86400);
        if (secs >= 86400) { days++; secs = 0; }
        LocalDate ld = LocalDate.of(1899, 12, 30).plusDays(days);
        LocalDateTime dt = LocalDateTime.of(ld, LocalTime.ofSecondOfDay(secs));
        return DateTimeFormatter.ofPattern(datePattern(code), Locale.US).format(dt);
    }

    private static String datePattern(String code) {
        String lc = code.toLowerCase(Locale.ROOT);
        boolean ampm = lc.contains("am/pm") || lc.contains("a/p");
        List<Object[]> t = new ArrayList<>(); // {kind, payload}
        int i = 0, n = code.length();
        while (i < n) {
            char ch = code.charAt(i);
            if (ch == '"') {
                int j = code.indexOf('"', i + 1);
                if (j < 0) j = n;
                t.add(new Object[] {'L', code.substring(i + 1, j)});
                i = j + 1;
            } else if (ch == '\\' && i + 1 < n) {
                t.add(new Object[] {'L', String.valueOf(code.charAt(i + 1))});
                i += 2;
            } else if (ch == '[') {
                int j = code.indexOf(']', i);
                i = j < 0 ? n : j + 1;
            } else if (code.regionMatches(true, i, "am/pm", 0, 5)) {
                t.add(new Object[] {'A', 0}); i += 5;
            } else if (code.regionMatches(true, i, "a/p", 0, 3)) {
                t.add(new Object[] {'A', 0}); i += 3;
            } else if ("ymdhsYMDHS".indexOf(ch) >= 0) {
                char k = Character.toLowerCase(ch);
                int j = i;
                while (j < n && Character.toLowerCase(code.charAt(j)) == k) j++;
                t.add(new Object[] {k, j - i});
                i = j;
            } else {
                t.add(new Object[] {'L', String.valueOf(ch)});
                i++;
            }
        }
        StringBuilder out = new StringBuilder(), lit = new StringBuilder();
        for (int x = 0; x < t.size(); x++) {
            char k = (Character) t.get(x)[0];
            if (k == 'L') { lit.append((String) t.get(x)[1]); continue; }
            if (lit.length() > 0) { out.append('\'').append(lit.toString().replace("'", "''")).append('\''); lit.setLength(0); }
            int len = k == 'A' ? 0 : (Integer) t.get(x)[1];
            switch (k) {
                case 'y': out.append(len <= 2 ? "yy" : "yyyy"); break;
                case 'd': out.append(len == 1 ? "d" : len == 2 ? "dd" : len == 3 ? "EEE" : "EEEE"); break;
                case 'h': out.append(ampm ? (len == 1 ? "h" : "hh") : (len == 1 ? "H" : "HH")); break;
                case 's': out.append(len == 1 ? "s" : "ss"); break;
                case 'A': out.append('a'); break;
                case 'm': {
                    char prev = 0, next = 0;
                    for (int y = x - 1; y >= 0; y--) if ((Character) t.get(y)[0] != 'L') { prev = (Character) t.get(y)[0]; break; }
                    for (int y = x + 1; y < t.size(); y++) if ((Character) t.get(y)[0] != 'L') { next = (Character) t.get(y)[0]; break; }
                    if (prev == 'h' || next == 's') out.append(len == 1 ? "m" : "mm");
                    else out.append(len == 1 ? "M" : len == 2 ? "MM" : len == 3 ? "MMM" : "MMMM");
                    break;
                }
                default: break;
            }
        }
        if (lit.length() > 0) out.append('\'').append(lit.toString().replace("'", "''")).append('\'');
        return out.toString();
    }
}
