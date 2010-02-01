package bixo.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HtmlUtils {

    // <META NAME="ROBOTS" CONTENT="xxx"> 
    private static final Pattern META_ROBOTS_PATTERN = Pattern
    .compile("(?is)<meta\\s+name\\s*=\\s*['\\\"]\\s*robots['\\\"]\\s+content\\s*=\\s*['\\\"]([^'\"]+)['\\\"]");

    // <META HTTP-EQUIV="PRAGMA" CONTENT="NO-CACHE">
    private static final Pattern META_PRAGMA_PATTERN = Pattern
    .compile("(?is)<meta\\s+http-equiv\\s*=\\s*['\\\"]\\s*pragma['\\\"]\\s+content\\s*=\\s*['\\\"]no-cache['\\\"]");

    // <META HTTP-EQUIV="CACHE-CONTROL" CONTENT="XXX">
    private static final Pattern META_CACHE_CONTROL_PATTERN = Pattern
    .compile("(?is)<meta\\s+http-equiv\\s*=\\s*['\\\"]\\s*cache-control['\\\"]\\s+content\\s*=\\s*['\\\"]([^'\"]+)['\\\"]");

    public static boolean hasNoArchiveMetaTags(String htmlText) {
        Matcher m = META_ROBOTS_PATTERN.matcher(htmlText);
        if (m.find()) {
            String[] directives = m.group(1).toLowerCase().split(",");
            for (String directive : directives) {
                if (directive.equals("none") || directive.equals("noarchive")) {
                    return true;
                }
            }
        }

        m = META_PRAGMA_PATTERN.matcher(htmlText);
        if (m.find()) {
            return true;
        }
        
        m = META_CACHE_CONTROL_PATTERN.matcher(htmlText);
        if (m.find()) {
            String[] directives = m.group(1).toLowerCase().split(",");
            for (String directive : directives) {
                if (directive.equals("no-cache") || directive.equals("no-store") || directive.equals("private")) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    public static boolean hasNoFollowMetaTags(String htmlText) {
        Matcher m = META_ROBOTS_PATTERN.matcher(htmlText);
        if (m.find()) {
            String[] directives = m.group(1).toLowerCase().split(",");
            for (String directive : directives) {
                if (directive.equals("none") || directive.equals("nofollow")) {
                    return true;
                }
            }
        }
        
        return false;
    }
}
