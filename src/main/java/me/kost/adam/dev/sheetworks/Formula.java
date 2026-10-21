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
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Formula parser and evaluator. Values are null (empty), Double, String, Boolean, or XlErr (thrown on use). */
public final class Formula {
    private Formula() {}

    public interface Node { Object eval(Ctx c); }

    public static final class Ctx {
        final Workbook wb; final Sheet sheet;
        Ctx(Workbook wb, Sheet sheet) { this.wb = wb; this.sheet = sheet; }
    }

    /** A rectangular reference to cells on one sheet. */
    public static final class Range {
        final Sheet sheet; final int r1, c1, r2, c2;
        Range(Sheet s, int r1, int c1, int r2, int c2) {
            sheet = s; this.r1 = Math.min(r1, r2); this.r2 = Math.max(r1, r2);
            this.c1 = Math.min(c1, c2); this.c2 = Math.max(c1, c2);
        }
        int rows() { return Math.max(0, Math.min(r2, sheet.maxRow) - r1 + 1); }
        int cols() { return Math.max(0, Math.min(c2, sheet.maxCol) - c1 + 1); }
        Object at(Ctx c, int i, int j) { return c.wb.value(sheet, r1 + i, c1 + j); }
        List<Object> flat(Ctx c) {
            List<Object> out = new ArrayList<>();
            int h = rows(), w = cols();
            for (int i = 0; i < h; i++) for (int j = 0; j < w; j++) {
                Object v = at(c, i, j);
                if (v instanceof XlErr) throw (XlErr) v;
                out.add(v);
            }
            return out;
        }
    }

    // ---------------------------------------------------------------- literals & text utilities

    private static final Pattern NUMBER = Pattern.compile("[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?");

    /** Interprets text typed into a non-formula cell: number, boolean, or text. */
    public static Object parseLiteral(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        if (raw.charAt(0) == '\'') return raw.substring(1);
        String t = raw.trim();
        if (NUMBER.matcher(t).matches()) {
            try { return Double.parseDouble(t); } catch (NumberFormatException e) { return raw; }
        }
        if (t.equalsIgnoreCase("TRUE")) return Boolean.TRUE;
        if (t.equalsIgnoreCase("FALSE")) return Boolean.FALSE;
        return raw;
    }

    /** True if the text would not be read back as the same text, so it needs a leading apostrophe. */
    public static boolean needsQuote(String s) {
        return s.startsWith("'") || s.startsWith("=") || !(parseLiteral(s) instanceof String);
    }

    private static final Pattern SEGMENTS = Pattern.compile("\"(?:[^\"]|\"\")*\"|'(?:[^']|'')*'");
    private static final Pattern CELLREF = Pattern.compile(
            "(?<![A-Za-z0-9_.$])(\\$?)([A-Za-z]{1,3})(\\$?)(\\d+)(?![A-Za-z0-9_(.])");
    private static final Pattern FUNCNAME = Pattern.compile("(?<![A-Za-z0-9_.$])([A-Za-z_][A-Za-z0-9_.]*)(?=\\()");

    private interface SegFn { String apply(String s); }

    private static String mapOutsideLiterals(String f, SegFn fn) {
        StringBuilder out = new StringBuilder();
        Matcher m = SEGMENTS.matcher(f);
        int last = 0;
        while (m.find()) {
            out.append(fn.apply(f.substring(last, m.start()))).append(m.group());
            last = m.end();
        }
        out.append(fn.apply(f.substring(last)));
        return out.toString();
    }

    /** Upper-cases function names and cell references (as Excel does when a formula is entered). */
    public static String normalize(String f) {
        return mapOutsideLiterals(f, seg -> {
            String a = replace(FUNCNAME, seg, m -> m.group(1).toUpperCase());
            return replace(CELLREF, a, m -> m.group().toUpperCase());
        });
    }

    private interface MatchFn { String apply(Matcher m); }

    private static String replace(Pattern p, String s, MatchFn fn) {
        Matcher m = p.matcher(s);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(s, last, m.start()).append(fn.apply(m));
            last = m.end();
        }
        return sb.append(s.substring(last)).toString();
    }

    /** Moves the relative references in a formula (starting with "=") by the given offsets. */
    public static String shift(String formula, int dr, int dc) {
        if (formula.isEmpty() || formula.charAt(0) != '=') return formula;
        return "=" + mapOutsideLiterals(formula.substring(1), seg -> replace(CELLREF, seg, m -> {
            int col = Sheet.parseCol(m.group(2)), row = Integer.parseInt(m.group(4)) - 1;
            boolean ac = !m.group(1).isEmpty(), ar = !m.group(3).isEmpty();
            int nc = ac ? col : col + dc, nr = ar ? row : row + dr;
            if (nc < 0 || nc >= Sheet.MAX_COLS || nr < 0 || nr >= Sheet.MAX_ROWS) return "#REF!";
            return m.group(1) + (ac ? m.group(2) : Sheet.colName(nc)) + m.group(3) + (nr + 1);
        }));
    }

    // ---------------------------------------------------------------- conversions

    static double num(Object o) {
        if (o == null) return 0;
        if (o instanceof Double) return (Double) o;
        if (o instanceof Boolean) return ((Boolean) o) ? 1 : 0;
        if (o instanceof XlErr) throw (XlErr) o;
        if (o instanceof Range) return num(single((Range) o, null));
        String s = o.toString().trim();
        if (NUMBER.matcher(s).matches()) return Double.parseDouble(s);
        throw XlErr.VALUE;
    }

    static String str(Object o) {
        if (o == null) return "";
        if (o instanceof Double) return NumFmt.general((Double) o);
        if (o instanceof Boolean) return ((Boolean) o) ? "TRUE" : "FALSE";
        return o.toString();
    }

    static boolean bool(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean) return (Boolean) o;
        if (o instanceof Double) return (Double) o != 0;
        if (o instanceof XlErr) throw (XlErr) o;
        String s = o.toString();
        if (s.equalsIgnoreCase("TRUE")) return true;
        if (s.equalsIgnoreCase("FALSE")) return false;
        throw XlErr.VALUE;
    }

    private static Object single(Range r, Ctx c) {
        if (r.r1 != r.r2 || r.c1 != r.c2 || c == null) throw XlErr.VALUE;
        return c.wb.value(r.sheet, r.r1, r.c1);
    }

    /** Reduces a possible range to a single value. */
    private static Object scalar(Ctx c, Object o) {
        if (o instanceof Range) o = single((Range) o, c);
        if (o instanceof XlErr) throw (XlErr) o;
        return o;
    }

    private static int rank(Object o) { return o instanceof Boolean ? 3 : o instanceof String ? 2 : 1; }

    static int compare(Object a, Object b) {
        if (a == null) a = (b instanceof String) ? "" : (b instanceof Boolean) ? Boolean.FALSE : (Object) 0.0;
        if (b == null) b = (a instanceof String) ? "" : (a instanceof Boolean) ? Boolean.FALSE : (Object) 0.0;
        int ra = rank(a), rb = rank(b);
        if (ra != rb) return Integer.compare(ra, rb);
        if (a instanceof Double) return Double.compare((Double) a, (Double) b);
        if (a instanceof Boolean) return Boolean.compare((Boolean) a, (Boolean) b);
        return a.toString().compareToIgnoreCase(b.toString());
    }

    private static double check(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) throw XlErr.NUM;
        return d;
    }

    // ---------------------------------------------------------------- parser

    public static Node parse(String src) {
        P p = new P(src);
        Node n = p.cmp();
        p.ws();
        if (p.i < p.s.length()) throw XlErr.ERR;
        return n;
    }

    private static final Pattern CELL = Pattern.compile("(\\$?)([A-Za-z]{1,3})(\\$?)(\\d+)");
    private static final Pattern COLONLY = Pattern.compile("\\$?([A-Za-z]{1,3})");
    private static final String[] CMP_OPS = {"<>", "<=", ">=", "=", "<", ">"};

    private static final class P {
        final String s; int i;
        P(String s) { this.s = s; }

        void ws() { while (i < s.length() && s.charAt(i) == ' ') i++; }
        boolean eat(String t) { ws(); if (s.startsWith(t, i)) { i += t.length(); return true; } return false; }
        boolean more() { return i < s.length(); }

        Node cmp() {
            Node l = cat();
            for (;;) {
                ws();
                String op = null;
                for (String o : CMP_OPS) if (s.startsWith(o, i)) { op = o; break; }
                if (op == null) return l;
                i += op.length();
                final Node a = l, b = cat();
                final String fop = op;
                l = c -> {
                    int k = compare(scalar(c, a.eval(c)), scalar(c, b.eval(c)));
                    switch (fop) {
                        case "=": return k == 0;
                        case "<>": return k != 0;
                        case "<": return k < 0;
                        case ">": return k > 0;
                        case "<=": return k <= 0;
                        default: return k >= 0;
                    }
                };
            }
        }

        Node cat() {
            Node l = add();
            while (eat("&")) {
                final Node a = l, b = add();
                l = c -> str(scalar(c, a.eval(c))) + str(scalar(c, b.eval(c)));
            }
            return l;
        }

        Node add() {
            Node l = mul();
            for (;;) {
                final boolean plus;
                if (eat("+")) plus = true; else if (eat("-")) plus = false; else return l;
                final Node a = l, b = mul();
                l = c -> check(plus ? num(scalar(c, a.eval(c))) + num(scalar(c, b.eval(c)))
                                    : num(scalar(c, a.eval(c))) - num(scalar(c, b.eval(c))));
            }
        }

        Node mul() {
            Node l = pow();
            for (;;) {
                final boolean times;
                if (eat("*")) times = true; else if (eat("/")) times = false; else return l;
                final Node a = l, b = pow();
                l = c -> {
                    double x = num(scalar(c, a.eval(c))), y = num(scalar(c, b.eval(c)));
                    if (times) return check(x * y);
                    if (y == 0) throw XlErr.DIV0;
                    return check(x / y);
                };
            }
        }

        Node pow() {
            Node l = unary();
            while (eat("^")) {
                final Node a = l, b = unary();
                l = c -> check(Math.pow(num(scalar(c, a.eval(c))), num(scalar(c, b.eval(c)))));
            }
            return l;
        }

        Node unary() {
            if (eat("-")) { final Node n = unary(); return c -> -num(scalar(c, n.eval(c))); }
            if (eat("+")) return unary();
            Node n = primary();
            while (eat("%")) { final Node a = n; n = c -> num(scalar(c, a.eval(c))) / 100.0; }
            return n;
        }

        Node primary() {
            ws();
            if (!more()) throw XlErr.ERR;
            char ch = s.charAt(i);
            if (ch == '(') {
                i++;
                Node n = cmp();
                if (!eat(")")) throw XlErr.ERR;
                return n;
            }
            if (ch == '"') {
                StringBuilder sb = new StringBuilder();
                int j = i + 1;
                for (;;) {
                    if (j >= s.length()) throw XlErr.ERR;
                    char x = s.charAt(j);
                    if (x == '"') {
                        if (j + 1 < s.length() && s.charAt(j + 1) == '"') { sb.append('"'); j += 2; continue; }
                        break;
                    }
                    sb.append(x); j++;
                }
                i = j + 1;
                final String lit = sb.toString();
                return c -> lit;
            }
            if (Character.isDigit(ch) || ch == '.') {
                int j = i;
                while (j < s.length() && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '.')) j++;
                if (j < s.length() && (s.charAt(j) == 'e' || s.charAt(j) == 'E')) {
                    int k = j + 1;
                    if (k < s.length() && (s.charAt(k) == '+' || s.charAt(k) == '-')) k++;
                    if (k < s.length() && Character.isDigit(s.charAt(k))) {
                        while (k < s.length() && Character.isDigit(s.charAt(k))) k++;
                        j = k;
                    }
                }
                final double d;
                try { d = Double.parseDouble(s.substring(i, j)); } catch (NumberFormatException e) { throw XlErr.ERR; }
                i = j;
                return c -> d;
            }
            if (ch == '#') {
                for (String e : new String[] {"#DIV/0!", "#VALUE!", "#REF!", "#NAME?", "#NUM!", "#N/A"}) {
                    if (s.regionMatches(true, i, e, 0, e.length())) {
                        i += e.length();
                        final XlErr err = XlErr.of(e);
                        return c -> { throw err; };
                    }
                }
                throw XlErr.ERR;
            }
            return refOrName();
        }

        private boolean identChar(char x) { return Character.isLetterOrDigit(x) || x == '_' || x == '.' || x == '$'; }

        private String word() {
            int j = i;
            while (j < s.length() && identChar(s.charAt(j))) j++;
            String w = s.substring(i, j);
            i = j;
            return w;
        }

        Node refOrName() {
            String sheetName = null;
            char ch = s.charAt(i);
            if (ch == '\'') {
                StringBuilder sb = new StringBuilder();
                int j = i + 1;
                for (;;) {
                    if (j >= s.length()) throw XlErr.ERR;
                    char x = s.charAt(j);
                    if (x == '\'') {
                        if (j + 1 < s.length() && s.charAt(j + 1) == '\'') { sb.append('\''); j += 2; continue; }
                        break;
                    }
                    sb.append(x); j++;
                }
                if (j + 1 >= s.length() || s.charAt(j + 1) != '!') throw XlErr.ERR;
                sheetName = sb.toString();
                i = j + 2;
            } else {
                int j = i;
                while (j < s.length() && identChar(s.charAt(j))) j++;
                if (j < s.length() && s.charAt(j) == '!') { sheetName = s.substring(i, j); i = j + 1; }
            }
            final String sn = sheetName;
            String w = word();
            if (w.isEmpty()) throw XlErr.ERR;
            if (sn == null && more() && s.charAt(i) == '(') {
                i++;
                List<Node> args = new ArrayList<>();
                if (!eat(")")) {
                    for (;;) {
                        args.add(cmp());
                        if (eat(",") || eat(";")) continue;
                        if (eat(")")) break;
                        throw XlErr.ERR;
                    }
                }
                return call(w.toUpperCase(), args);
            }
            Matcher m = CELL.matcher(w);
            if (m.matches()) {
                final int c1 = Sheet.parseCol(m.group(2)), r1 = Integer.parseInt(m.group(4)) - 1;
                if (c1 >= Sheet.MAX_COLS || r1 < 0 || r1 >= Sheet.MAX_ROWS) return c -> { throw XlErr.NAME; };
                if (more() && s.charAt(i) == ':') {
                    i++;
                    String w2 = word();
                    Matcher m2 = CELL.matcher(w2);
                    if (!m2.matches()) throw XlErr.ERR;
                    final int c2 = Sheet.parseCol(m2.group(2)), r2 = Integer.parseInt(m2.group(4)) - 1;
                    if (c2 >= Sheet.MAX_COLS || r2 < 0 || r2 >= Sheet.MAX_ROWS) throw XlErr.ERR;
                    return c -> new Range(sheetOf(c, sn), r1, c1, r2, c2);
                }
                return c -> {
                    Object v = c.wb.value(sheetOf(c, sn), r1, c1);
                    if (v instanceof XlErr) throw (XlErr) v;
                    return v;
                };
            }
            Matcher mc = COLONLY.matcher(w);
            if (mc.matches() && more() && s.charAt(i) == ':') {
                i++;
                Matcher mc2 = COLONLY.matcher(word());
                if (!mc2.matches()) throw XlErr.ERR;
                final int c1 = Sheet.parseCol(mc.group(1)), c2 = Sheet.parseCol(mc2.group(1));
                return c -> new Range(sheetOf(c, sn), 0, c1, Sheet.MAX_ROWS - 1, c2);
            }
            if (sn == null && w.equalsIgnoreCase("TRUE")) return c -> Boolean.TRUE;
            if (sn == null && w.equalsIgnoreCase("FALSE")) return c -> Boolean.FALSE;
            return c -> { throw XlErr.NAME; };
        }
    }

    private static Sheet sheetOf(Ctx c, String name) {
        if (name == null) return c.sheet;
        Sheet s = c.wb.find(name);
        if (s == null) throw XlErr.REF;
        return s;
    }

    // ---------------------------------------------------------------- functions

    private static Node call(final String name, final List<Node> args) {
        final Node[] a = args.toArray(new Node[0]);
        switch (name) {
            case "IF":
                return c -> {
                    if (a.length < 2) throw XlErr.ERR;
                    if (bool(scalar(c, a[0].eval(c)))) return scalar(c, a[1].eval(c));
                    return a.length > 2 ? scalar(c, a[2].eval(c)) : Boolean.FALSE;
                };
            case "IFERROR":
                return c -> {
                    if (a.length != 2) throw XlErr.ERR;
                    try { return scalar(c, a[0].eval(c)); } catch (XlErr e) { return scalar(c, a[1].eval(c)); }
                };
            default:
                return c -> {
                    Object[] v = new Object[a.length];
                    for (int k = 0; k < a.length; k++) v[k] = a[k].eval(c);
                    return invoke(name, v, c);
                };
        }
    }

    private static Object arg(Object[] v, int i) {
        if (i >= v.length) throw XlErr.ERR;
        return v[i];
    }

    private static double narg(Ctx c, Object[] v, int i) { return num(scalar(c, arg(v, i))); }

    private static double narg(Ctx c, Object[] v, int i, double dflt) { return i < v.length ? narg(c, v, i) : dflt; }

    /** Numbers found in the arguments: ranges contribute only numeric cells, direct arguments are converted. */
    private static List<Double> numbers(Ctx c, Object[] v) {
        List<Double> out = new ArrayList<>();
        for (Object o : v) {
            if (o instanceof Range) {
                for (Object x : ((Range) o).flat(c)) if (x instanceof Double) out.add((Double) x);
            } else if (o != null) {
                out.add(num(o));
            }
        }
        return out;
    }

    private static List<Object> items(Ctx c, Object[] v) {
        List<Object> out = new ArrayList<>();
        for (Object o : v) {
            if (o instanceof Range) out.addAll(((Range) o).flat(c)); else out.add(o);
        }
        return out;
    }

    private static Predicate<Object> criterion(Object k) {
        String op = "=";
        Object target = k;
        if (k instanceof String) {
            String t = (String) k, rest = t;
            for (String o : CMP_OPS) if (t.startsWith(o)) { op = o; rest = t.substring(o.length()); break; }
            target = rest.isEmpty() ? "" : parseLiteral(rest);
        }
        final String fop = op;
        final Object t = target;
        return v -> {
            if (t instanceof Double) {
                if (!(v instanceof Double)) return fop.equals("<>");
                return cmpOp(fop, Double.compare((Double) v, (Double) t));
            }
            String x = v == null ? "" : str(v), y = str(t);
            if (fop.equals("=")) return x.equalsIgnoreCase(y);
            if (fop.equals("<>")) return !x.equalsIgnoreCase(y);
            return v instanceof String && cmpOp(fop, x.compareToIgnoreCase(y));
        };
    }

    private static boolean cmpOp(String op, int k) {
        switch (op) {
            case "=": return k == 0;
            case "<>": return k != 0;
            case "<": return k < 0;
            case ">": return k > 0;
            case "<=": return k <= 0;
            default: return k >= 0;
        }
    }

    private static double round(double x, double digits, RoundingMode mode) {
        if (Double.isNaN(x) || Double.isInfinite(x)) throw XlErr.NUM;
        return new BigDecimal(Double.toString(x)).setScale((int) digits, mode).doubleValue();
    }

    private static Object invoke(String name, Object[] v, Ctx c) {
        switch (name) {
            case "SUM": { double t = 0; for (double d : numbers(c, v)) t += d; return check(t); }
            case "PRODUCT": { double t = 1; for (double d : numbers(c, v)) t *= d; return check(t); }
            case "AVERAGE": {
                List<Double> l = numbers(c, v);
                if (l.isEmpty()) throw XlErr.DIV0;
                double t = 0; for (double d : l) t += d;
                return t / l.size();
            }
            case "MEDIAN": {
                List<Double> l = numbers(c, v);
                if (l.isEmpty()) throw XlErr.NUM;
                java.util.Collections.sort(l);
                int n = l.size();
                return n % 2 == 1 ? l.get(n / 2) : (l.get(n / 2 - 1) + l.get(n / 2)) / 2;
            }
            case "MIN": { double m = Double.POSITIVE_INFINITY; for (double d : numbers(c, v)) m = Math.min(m, d); return Double.isInfinite(m) ? 0.0 : m; }
            case "MAX": { double m = Double.NEGATIVE_INFINITY; for (double d : numbers(c, v)) m = Math.max(m, d); return Double.isInfinite(m) ? 0.0 : m; }
            case "COUNT": return (double) numbers(c, v).size();
            case "COUNTA": { int n = 0; for (Object o : items(c, v)) if (o != null) n++; return (double) n; }
            case "ROUND": return round(narg(c, v, 0), narg(c, v, 1, 0), RoundingMode.HALF_UP);
            case "ROUNDUP": return round(narg(c, v, 0), narg(c, v, 1, 0), RoundingMode.UP);
            case "ROUNDDOWN": return round(narg(c, v, 0), narg(c, v, 1, 0), RoundingMode.DOWN);
            case "ABS": return Math.abs(narg(c, v, 0));
            case "INT": return Math.floor(narg(c, v, 0));
            case "SQRT": { double x = narg(c, v, 0); if (x < 0) throw XlErr.NUM; return Math.sqrt(x); }
            case "POWER": return check(Math.pow(narg(c, v, 0), narg(c, v, 1)));
            case "MOD": {
                double x = narg(c, v, 0), y = narg(c, v, 1);
                if (y == 0) throw XlErr.DIV0;
                return x - y * Math.floor(x / y);
            }
            case "PI": return Math.PI;
            case "TODAY": return (double) (LocalDate.now().toEpochDay() + 25569);
            case "NOW": {
                LocalDateTime n = LocalDateTime.now();
                return LocalDate.now().toEpochDay() + 25569 + n.toLocalTime().toSecondOfDay() / 86400.0;
            }
            case "AND": case "OR": {
                boolean and = name.equals("AND"), res = and, any = false;
                for (Object o : items(c, v)) {
                    if (o == null || (o instanceof String && !(arg(v, 0) instanceof String))) continue;
                    any = true;
                    boolean b = bool(o);
                    if (and) res &= b; else res |= b;
                }
                if (!any) throw XlErr.VALUE;
                return res;
            }
            case "NOT": return !bool(scalar(c, arg(v, 0)));
            case "CONCAT": case "CONCATENATE": {
                StringBuilder sb = new StringBuilder();
                for (Object o : items(c, v)) sb.append(str(o));
                return sb.toString();
            }
            case "UPPER": return str(scalar(c, arg(v, 0))).toUpperCase();
            case "LOWER": return str(scalar(c, arg(v, 0))).toLowerCase();
            case "LEN": return (double) str(scalar(c, arg(v, 0))).length();
            case "TRIM": return str(scalar(c, arg(v, 0))).trim().replaceAll(" +", " ");
            case "LEFT": { String s = str(scalar(c, arg(v, 0))); int n = (int) narg(c, v, 1, 1); if (n < 0) throw XlErr.VALUE; return s.substring(0, Math.min(n, s.length())); }
            case "RIGHT": { String s = str(scalar(c, arg(v, 0))); int n = (int) narg(c, v, 1, 1); if (n < 0) throw XlErr.VALUE; return s.substring(Math.max(0, s.length() - n)); }
            case "MID": {
                String s = str(scalar(c, arg(v, 0)));
                int st = (int) narg(c, v, 1), n = (int) narg(c, v, 2);
                if (st < 1 || n < 0) throw XlErr.VALUE;
                if (st > s.length()) return "";
                return s.substring(st - 1, Math.min(s.length(), st - 1 + n));
            }
            case "SUMIF": case "COUNTIF": {
                if (!(arg(v, 0) instanceof Range)) throw XlErr.VALUE;
                Range r = (Range) v[0];
                Predicate<Object> p = criterion(scalar(c, arg(v, 1)));
                Range sum = (name.equals("SUMIF") && v.length > 2 && v[2] instanceof Range) ? (Range) v[2] : r;
                double t = 0;
                for (int i = 0; i < r.rows(); i++) for (int j = 0; j < r.cols(); j++) {
                    Object x = r.at(c, i, j);
                    if (x instanceof XlErr) throw (XlErr) x;
                    if (!p.test(x)) continue;
                    if (name.equals("COUNTIF")) { t++; continue; }
                    Object y = sum.at(c, i, j);
                    if (y instanceof Double) t += (Double) y;
                }
                return t;
            }
            case "VLOOKUP": {
                Object key = scalar(c, arg(v, 0));
                if (!(arg(v, 1) instanceof Range)) throw XlErr.VALUE;
                Range r = (Range) v[1];
                int col = (int) narg(c, v, 2);
                if (col < 1 || col > r.c2 - r.c1 + 1) throw XlErr.REF;
                boolean approx = v.length < 4 || bool(scalar(c, v[3]));
                int found = -1;
                for (int i = 0; i < r.rows(); i++) {
                    Object x = r.at(c, i, 0);
                    if (x == null || x instanceof XlErr || rank(x) != rank(key)) continue;
                    int k = compare(x, key);
                    if (!approx) { if (k == 0) { found = i; break; } }
                    else if (k <= 0) found = i;
                    else break;
                }
                if (found < 0) throw XlErr.NA;
                Object res = r.at(c, found, col - 1);
                if (res instanceof XlErr) throw (XlErr) res;
                return res;
            }
            default:
                throw XlErr.NAME;
        }
    }

    static List<String> functionNames() {
        return Arrays.asList("SUM", "PRODUCT", "AVERAGE", "MEDIAN", "MIN", "MAX", "COUNT", "COUNTA", "IF", "IFERROR",
                "ROUND", "ROUNDUP", "ROUNDDOWN", "ABS", "INT", "SQRT", "POWER", "MOD", "PI", "TODAY", "NOW", "AND", "OR",
                "NOT", "CONCAT", "CONCATENATE", "UPPER", "LOWER", "LEN", "TRIM", "LEFT", "RIGHT", "MID", "SUMIF",
                "COUNTIF", "VLOOKUP");
    }
}
