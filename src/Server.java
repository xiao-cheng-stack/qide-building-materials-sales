import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/**
 * 建材市场微信销售小系统 —— 后端
 *
 * 技术：JDK 自带 HttpServer（不用装 Tomcat/Spring）+ JDBC + SQLite
 * 前端：web/index.html（微信风格 H5，手机浏览器打开就像小程序）
 *
 * 启动后访问 http://localhost:8080
 */
public class Server {

    private static final String DB_URL = "jdbc:sqlite:shop.db";
    private static final int PORT = 8080;
    private static final String WEB_DIR = "web";

    public static void main(String[] args) throws Exception {
        Class.forName("org.sqlite.JDBC");
        initDb();

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

        // 商品列表 / 按分类筛选
        server.createContext("/api/products", ex -> {
            try {
                String cat = queryParam(ex, "cat");
                sendJson(ex, productList(cat));
            } catch (Exception e) {
                sendError(ex, e.getMessage());
            }
        });

        // 提交询价（客户留电话 + 意向商品）
        server.createContext("/api/inquiry", ex -> {
            try {
                if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
                    String body = readBody(ex);
                    sendJson(ex, "{\"ok\":" + saveInquiry(body) + "}");
                } else {
                    sendJson(ex, inquiryList());
                }
            } catch (Exception e) {
                sendError(ex, e.getMessage());
            }
        });

        // 销售后台：询价记录
        server.createContext("/api/inquiries", ex -> {
            try {
                sendJson(ex, inquiryList());
            } catch (Exception e) {
                sendError(ex, e.getMessage());
            }
        });

        // 简单统计
        server.createContext("/api/stats", ex -> {
            try {
                sendJson(ex, stats());
            } catch (Exception e) {
                sendError(ex, e.getMessage());
            }
        });

        // 静态页面
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            if ("/".equals(path)) {
                path = "/index.html";
            }
            File f = new File(WEB_DIR, path.replace("..", ""));
            if (!f.exists()) {
                sendText(ex, 404, "404 Not Found");
                return;
            }
            byte[] bytes;
            try (FileInputStream in = new FileInputStream(f)) {
                bytes = in.readAllBytes();
            }
            ex.getResponseHeaders().set("Content-Type", contentType(f.getName()));
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });

        server.start();
        System.out.println("========================================");
        System.out.println(" 建材市场微信销售小系统已启动");
        System.out.println(" 请在浏览器打开: http://localhost:8080");
        System.out.println(" 手机同一 WiFi 下可用电脑 IP 访问");
        System.out.println("========================================");
    }

    // ---------------- 数据库 ----------------

    private static Connection conn() throws SQLException {
        return DriverManager.getConnection(DB_URL);
    }

    private static void initDb() throws SQLException {
        try (Connection c = conn(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS product (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "name TEXT, spec TEXT, price REAL, unit TEXT, cat TEXT, stock INTEGER)");
            st.execute("CREATE TABLE IF NOT EXISTS inquiry (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "name TEXT, phone TEXT, product TEXT, remark TEXT," +
                    "create_time TEXT DEFAULT (datetime('now','localtime')))");

            // 没有数据就塞几条建材样例
            ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM product");
            if (rs.next() && rs.getInt(1) == 0) {
                String[][] seed = {
                        {"800x800 通体大理石瓷砖", "亮光灰 / 一级品", "68", "片", "瓷砖", "500"},
                        {"600x600 哑光防滑地砖", "工程款 / 优等品", "32", "片", "瓷砖", "1200"},
                        {"实木复合地板", "橡木 / 12mm厚", "158", "平方米", "地板", "300"},
                        {"智能马桶一体机", "虹吸式 / 带烘干", "1280", "套", "卫浴", "40"},
                        {"恒温花洒套装", "全铜主体 / 三档", "399", "套", "卫浴", "80"},
                        {"生态板柜体", "E0 级 / 18mm", "210", "张", "板材", "260"},
                        {"断桥铝门窗", "1.8 壁厚 / 双层中空", "680", "平方米", "门窗", "60"}
                };
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO product(name,spec,price,unit,cat,stock) VALUES(?,?,?,?,?,?)")) {
                    for (String[] row : seed) {
                        ps.setString(1, row[0]);
                        ps.setString(2, row[1]);
                        ps.setDouble(3, Double.parseDouble(row[2]));
                        ps.setString(4, row[3]);
                        ps.setString(5, row[4]);
                        ps.setInt(6, Integer.parseInt(row[5]));
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
        }
    }

    private static String productList(String cat) throws SQLException {
        StringBuilder sb = new StringBuilder("[");
        String sql = "SELECT * FROM product" + (isEmpty(cat) ? "" : " WHERE cat = ?") + " ORDER BY id";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            if (!isEmpty(cat)) {
                ps.setString(1, cat);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (sb.length() > 1) {
                        sb.append(",");
                    }
                    sb.append("{")
                            .append("\"id\":").append(rs.getInt("id")).append(",")
                            .append("\"name\":\"").append(esc(rs.getString("name"))).append("\",")
                            .append("\"spec\":\"").append(esc(rs.getString("spec"))).append("\",")
                            .append("\"price\":").append(rs.getDouble("price")).append(",")
                            .append("\"unit\":\"").append(esc(rs.getString("unit"))).append("\",")
                            .append("\"cat\":\"").append(esc(rs.getString("cat"))).append("\",")
                            .append("\"stock\":").append(rs.getInt("stock"))
                            .append("}");
                }
            }
        }
        return sb.append("]").toString();
    }

    private static boolean saveInquiry(String json) throws SQLException {
        String name = jsonStr(json, "name");
        String phone = jsonStr(json, "phone");
        String product = jsonStr(json, "product");
        String remark = jsonStr(json, "remark");
        if (isEmpty(name) || isEmpty(phone)) {
            return false;
        }
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO inquiry(name, phone, product, remark) VALUES(?,?,?,?)")) {
            ps.setString(1, name);
            ps.setString(2, phone);
            ps.setString(3, product);
            ps.setString(4, remark);
            return ps.executeUpdate() > 0;
        }
    }

    private static String inquiryList() throws SQLException {
        StringBuilder sb = new StringBuilder("[");
        try (Connection c = conn(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM inquiry ORDER BY id DESC LIMIT 50")) {
            while (rs.next()) {
                if (sb.length() > 1) {
                    sb.append(",");
                }
                sb.append("{")
                        .append("\"id\":").append(rs.getInt("id")).append(",")
                        .append("\"name\":\"").append(esc(rs.getString("name"))).append("\",")
                        .append("\"phone\":\"").append(esc(rs.getString("phone"))).append("\",")
                        .append("\"product\":\"").append(esc(rs.getString("product"))).append("\",")
                        .append("\"remark\":\"").append(esc(rs.getString("remark"))).append("\",")
                        .append("\"time\":\"").append(esc(rs.getString("create_time"))).append("\"")
                        .append("}");
            }
        }
        return sb.append("]").toString();
    }

    private static String stats() throws SQLException {
        try (Connection c = conn(); Statement st = c.createStatement()) {
            int products = 0, today = 0, total = 0;
            ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM product");
            if (rs.next()) products = rs.getInt(1);
            rs = st.executeQuery("SELECT COUNT(*) FROM inquiry WHERE date(create_time) = date('now','localtime')");
            if (rs.next()) today = rs.getInt(1);
            rs = st.executeQuery("SELECT COUNT(*) FROM inquiry");
            if (rs.next()) total = rs.getInt(1);
            return "{\"products\":" + products + ",\"todayInquiry\":" + today + ",\"totalInquiry\":" + total + "}";
        }
    }

    // ---------------- 工具方法 ----------------

    private static String queryParam(HttpExchange ex, String key) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return "";
        for (String kv : q.split("&")) {
            String[] arr = kv.split("=", 2);
            if (arr.length == 2 && arr[0].equals(key)) {
                return URLDecoder.decode(arr[1], StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 从 JSON 字符串里取字段值（不引第三方库，手写够用）
     */
    private static String jsonStr(String json, String key) {
        String pat = "\"" + key + "\"";
        int i = json.indexOf(pat);
        if (i < 0) return "";
        int colon = json.indexOf(":", i + pat.length());
        if (colon < 0) return "";
        int start = json.indexOf("\"", colon + 1);
        if (start < 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int j = start + 1; j < json.length(); j++) {
            char ch = json.charAt(j);
            if (ch == '\\') {
                j++;
                if (j < json.length()) sb.append(json.charAt(j));
            } else if (ch == '"') {
                break;
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static void sendJson(HttpExchange ex, String json) throws IOException {
        sendText(ex, 200, json, "application/json; charset=utf-8");
    }

    private static void sendError(HttpExchange ex, String msg) throws IOException {
        sendText(ex, 500, "{\"error\":\"" + esc(msg) + "\"}", "application/json; charset=utf-8");
    }

    private static void sendText(HttpExchange ex, int code, String text) throws IOException {
        sendText(ex, code, text, "text/plain; charset=utf-8");
    }

    private static void sendText(HttpExchange ex, int code, String text, String type) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static String contentType(String name) {
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg")) return "image/jpeg";
        return "application/octet-stream";
    }
}
