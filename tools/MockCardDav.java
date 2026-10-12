// Fake CardDAV server for trying sync on the emulator: java tools/MockCardDav.java
// Then sign in to Rolodex with any email/password and server http://10.0.2.2:5233/
// With "ox" (java tools/MockCardDav.java ox) it drops numbers that don't fit OX's fixed slots, the worst case for mailbox.org:
// 2 mobile, 2 work, 2 home, 1 other (X-OTHER); numbers with only a custom label fit none.
// All contacts are made up.
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class MockCardDav {
    static final Map<String, Map<String, String[]>> books = new LinkedHashMap<>(); // book -> href -> {etag, vcard}
    static int tag = 1;
    static boolean ox;

    public static void main(String[] a) throws Exception {
        ox = a.length > 0 && a[0].equals("ox");
        books.put("/dav/contacts/", new LinkedHashMap<>());
        books.put("/dav/collected/", new LinkedHashMap<>());
        add("/dav/contacts/", "ada", card("ada", "Ada Lovelace", "Lovelace;Ada;;;",
            "TEL;TYPE=CELL:+1 555 0100", "TEL;TYPE=WORK,FAX:+1 555 0101", "item1.TEL:+1 555 0102", "item1.X-ABLabel:Lab",
            "EMAIL;TYPE=INTERNET,HOME:ada@example.com", "ADR;TYPE=HOME:;;12 St James Square;London;;SW1;UK",
            "BDAY:1815-12-10", "ORG:Analytical Engines;Research", "TITLE:Programmer",
            "NOTE:Made-up test contact", "CATEGORIES:Friends", "X-CUSTOM-THING:keep me"));
        add("/dav/contacts/", "grace", card("grace", "Grace Hopper", "Hopper;Grace;;Rear Admiral;",
            "TEL;TYPE=WORK:+1 555 0110", "EMAIL;TYPE=WORK:grace@example.com", "X-ANNIVERSARY:--06-15",
            "IMPP;X-SERVICE-TYPE=Signal:sgnl:+15550110", "RELATED;TYPE=friend;VALUE=text:Ada"));
        add("/dav/contacts/", "acme", card("acme", "Acme Bakery", ";;;;",
            "ORG:Acme Bakery", "TEL;TYPE=WORK:+1 555 0120", "URL:https://bakery.example.com"));
        add("/dav/contacts/", "lin", card("lin", "Lin Ito", "Ito;Lin;;;", "TEL;TYPE=CELL:+1 555 0130", "TEL;TYPE=HOME:+1 555 0131"));
        add("/dav/contacts/", "team", "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:team\r\nKIND:group\r\nFN:Team\r\nMEMBER:urn:uuid:ada\r\nEND:VCARD\r\n");
        add("/dav/collected/", "someone", card("someone", "someone@example.org", ";;;;", "EMAIL:someone@example.org"));

        HttpServer s = HttpServer.create(new InetSocketAddress(5233), 0);
        s.createContext("/", MockCardDav::handle);
        s.start();
        System.out.println("Mock CardDAV on :5233");
    }

    static String card(String uid, String fn, String n, String... lines) {
        StringBuilder b = new StringBuilder("BEGIN:VCARD\r\nVERSION:3.0\r\nUID:" + uid + "\r\nFN:" + fn + "\r\nN:" + n + "\r\n");
        for (String l : lines) b.append(l).append("\r\n");
        return b.append("END:VCARD\r\n").toString();
    }

    static void add(String book, String name, String vcf) {
        books.get(book).put(book + name + ".vcf", new String[]{"\"" + (tag++) + "\"", vcf});
    }

    static String ctag(String book) {
        return "" + books.get(book).values().stream().map(v -> v[0]).reduce("", String::concat).hashCode();
    }

    static synchronized void handle(HttpExchange x) throws IOException {
        String m = x.getRequestMethod(), p = x.getRequestURI().getPath();
        String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String im = x.getRequestHeaders().getFirst("If-Match"), inm = x.getRequestHeaders().getFirst("If-None-Match");
        System.out.println(m + " " + p + (im != null ? " If-Match " + im : "") + (inm != null ? " If-None-Match " + inm : ""));
        String book = books.keySet().stream().filter(p::startsWith).findFirst().orElse(null);
        switch (m) {
            case "PROPFIND" -> {
                StringBuilder r = new StringBuilder();
                if (body.contains("current-user-principal")) r.append(resp(p, "<d:current-user-principal><d:href>/principals/me/</d:href></d:current-user-principal>"));
                else if (body.contains("addressbook-home-set")) r.append(resp(p, "<c:addressbook-home-set><d:href>/dav/</d:href></c:addressbook-home-set>"));
                else if (book == null) {
                    r.append(resp("/dav/", "<d:resourcetype><d:collection/></d:resourcetype>"));
                    for (String b : books.keySet()) {
                        String name = b.contains("collected") ? "Collected addresses" : "Contacts";
                        r.append(resp(b, "<d:resourcetype><d:collection/><c:addressbook/></d:resourcetype><d:displayname>" + name
                            + "</d:displayname><cs:getctag>" + ctag(b) + "</cs:getctag>"
                            + "<d:current-user-privilege-set><d:privilege><d:read/></d:privilege><d:privilege><d:write/></d:privilege></d:current-user-privilege-set>"));
                    }
                } else {
                    r.append(resp(book, "<d:resourcetype><d:collection/><c:addressbook/></d:resourcetype>"));
                    for (var e : books.get(book).entrySet())
                        r.append(resp(e.getKey(), "<d:resourcetype/><d:getetag>" + e.getValue()[0] + "</d:getetag>"));
                }
                multi(x, r.toString());
            }
            case "REPORT" -> {
                StringBuilder r = new StringBuilder();
                for (String href : body.split("<d:href>")) {
                    if (!href.contains("</d:href>")) continue;
                    String h = href.substring(0, href.indexOf("</d:href>"));
                    String[] v = books.get(book).get(h);
                    if (v == null) r.append("<d:response><d:href>" + h + "</d:href><d:status>HTTP/1.1 404 Not Found</d:status></d:response>");
                    else r.append(resp(h, "<d:getetag>" + v[0] + "</d:getetag><c:address-data>" + v[1].replace("&", "&amp;").replace("<", "&lt;") + "</c:address-data>"));
                }
                multi(x, r.toString());
            }
            case "GET" -> {
                String[] v = book == null ? null : books.get(book).get(p);
                if (v == null) { send(x, 404, ""); return; }
                x.getResponseHeaders().add("ETag", v[0]);
                send(x, 200, v[1]);
            }
            case "PUT" -> {
                String[] cur = books.get(book).get(p);
                if ((im != null && (cur == null || !cur[0].equals(im))) || ("*".equals(inm) && cur != null)) { send(x, 412, ""); return; }
                String etag = "\"" + (tag++) + "\"";
                if (ox) body = oxSlots(body);
                books.get(book).put(p, new String[]{etag, body});
                System.out.println(body);
                x.getResponseHeaders().add("ETag", etag);
                send(x, cur == null ? 201 : 204, "");
            }
            case "DELETE" -> {
                String[] cur = books.get(book).get(p);
                if (cur == null) { send(x, 404, ""); return; }
                if (im != null && !cur[0].equals(im)) { send(x, 412, ""); return; }
                books.get(book).remove(p);
                send(x, 204, "");
            }
            default -> send(x, 405, "");
        }
    }

    /** Keeps only as many numbers of each kind as OX has fields for. */
    static String oxSlots(String vcf) {
        Map<String, Integer> used = new HashMap<>(), room = Map.of("cell", 2, "work", 2, "home", 2, "other", 1, "none", 0);
        StringBuilder out = new StringBuilder();
        for (String line : vcf.split("\r\n")) {
            String head = line.contains(":") ? line.substring(0, line.indexOf(':')).toUpperCase() : "";
            if (head.matches("(ITEM\\d+\\.)?TEL.*") && !head.contains("FAX") && !head.contains("PAGER")) {
                String kind = head.contains("CELL") ? "cell" : head.contains("WORK") ? "work" : head.contains("HOME") ? "home" : head.contains("X-OTHER") ? "other" : "none";
                int n = used.merge(kind, 1, Integer::sum);
                if (n > room.get(kind)) { System.out.println("OX drops: " + line); continue; }
            }
            out.append(line).append("\r\n");
        }
        return out.toString();
    }

    static String resp(String href, String props) {
        return "<d:response><d:href>" + href + "</d:href><d:propstat><d:prop>" + props + "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>";
    }

    static void multi(HttpExchange x, String inner) throws IOException {
        x.getResponseHeaders().add("Content-Type", "application/xml; charset=utf-8");
        send(x, 207, "<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:carddav\" xmlns:cs=\"http://calendarserver.org/ns/\">" + inner + "</d:multistatus>");
    }

    static void send(HttpExchange x, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        x.sendResponseHeaders(code, b.length == 0 ? -1 : b.length);
        if (b.length > 0) x.getResponseBody().write(b);
        x.close();
    }
}
