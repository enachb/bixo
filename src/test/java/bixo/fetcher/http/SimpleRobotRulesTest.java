package bixo.fetcher.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.util.Arrays;

import javax.servlet.http.HttpServletResponse;

import org.apache.hadoop.io.BytesWritable;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

import bixo.config.UserAgent;
import bixo.datum.FetchedDatum;
import bixo.datum.ScoredUrlDatum;
import bixo.exceptions.BaseFetchException;


public class SimpleRobotRulesTest {
    private static final String LF = "\n";
    private static final String CR = "\r";
    private static final String CRLF = "\r\n";
	private static final String FAKE_ROBOTS_URL = "http://domain.com";
    
    private static SimpleRobotRules createRobotRules(String crawlerName, byte[] content) {
    	return new SimpleRobotRules(crawlerName, FAKE_ROBOTS_URL, content);
    }
    

    @Test
    public void testEmptyRules() throws MalformedURLException {
        SimpleRobotRules rules = createRobotRules("Any-darn-crawler", "".getBytes());
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/anypage.html"));
    }
    
    @Test
    public void testMatchAgainstEmail() throws MalformedURLException, BaseFetchException {
    	// The "crawler@domain.com" email address shouldn't trigger a match against the
    	// "crawler" user agent name in the robots.txt file.
        final String simpleRobotsTxt = "User-agent: crawler" + CRLF
        + "Disallow: /";

        IHttpFetcher fetcher = Mockito.mock(IHttpFetcher.class);
        FetchedDatum datum = Mockito.mock(FetchedDatum.class);
        Mockito.when(datum.getContent()).thenReturn(new BytesWritable(simpleRobotsTxt.getBytes()));
        Mockito.when(fetcher.get(Mockito.any(ScoredUrlDatum.class))).thenReturn(datum);
        UserAgent userAgent = new UserAgent("testAgent", "crawler@domain.com", "http://www.domain.com");
        Mockito.when(fetcher.getUserAgent()).thenReturn(userAgent);
        
        SimpleRobotRules rules = new SimpleRobotRules(fetcher, "http://www.domain.com/anypage.html");
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/anypage.html"));
    }
    
    @Test
    public void testCommentedOutLines() throws MalformedURLException {
        final String simpleRobotsTxt =  "#user-agent: testAgent" + LF
        + LF
        + "#allow: /index.html"+ LF
        + "#allow: /test"+ LF
        + LF
        + "#user-agent: test"+ LF
        + LF
        + "#allow: /index.html"+ LF
        + "#disallow: /test"+ LF
        + LF
        + "#user-agent: someAgent"+ LF
        + LF
        + "#disallow: /index.html"+ LF
        + "#disallow: /test"+ LF
        + LF;

        SimpleRobotRules rules = createRobotRules("Any-darn-crawler", simpleRobotsTxt.getBytes());
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/anypage.html"));
    }
    
    @Test
    public void testRobotsTxtAlwaysAllowed() throws MalformedURLException {
        final String simpleRobotsTxt = "User-agent: *" + CRLF
        + "Disallow: /";

        SimpleRobotRules rules = createRobotRules("any-darn-crawler", simpleRobotsTxt.getBytes());
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/robots.txt"));
    }
    
    @Test
    public void testAgentNotListed() throws MalformedURLException {
        // Access is assumed to be allowed, if no rules match an agent.
        final String simpleRobotsTxt = "User-agent: crawler1" + CRLF
        + "Disallow: /index.html" + CRLF
        + "Allow: /" + CRLF
        + CRLF
        + "User-agent: crawler2" + CRLF
        + "Disallow: /";

        SimpleRobotRules rules = createRobotRules("crawler3", simpleRobotsTxt.getBytes());
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/anypage.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/index.html"));
    }
    
    @Test
    public void testNonAsciiEncoding() throws UnsupportedEncodingException, MalformedURLException {
        final String simpleRobotsTxt = "User-agent: *" + " # \u00A2 \u20B5" + CRLF
        + "Disallow:";

        SimpleRobotRules rules = createRobotRules("Any-darn-crawler", simpleRobotsTxt.getBytes("UTF-8"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/anypage.html"));
    }
    
    @Test
    public void testSimplestAllowAll() throws MalformedURLException {
        final String simpleRobotsTxt = "User-agent: *" + CRLF
        + "Disallow:";
        
        SimpleRobotRules rules = createRobotRules("Any-darn-crawler", simpleRobotsTxt.getBytes());
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/anypage.html"));
    }
    
    @Test
    public void testMixedEndings() throws MalformedURLException {
        final String mixedEndingsRobotsTxt = "# /robots.txt for http://www.fict.org/" + CRLF
        + "# comments to webmaster@fict.org" + CR
        + LF
        + "User-agent: unhipbot" + LF
        + "Disallow: /" + CR
        + "" + CRLF
        + "User-agent: webcrawler" + LF
        + "User-agent: excite" + CR
        + "Disallow: " + "\u0085"
        + CR
        + "User-agent: *" + CRLF
        + "Disallow: /org/plans.html" + LF
        + "Allow: /org/" + CR
        + "Allow: /serv" + CRLF
        + "Allow: /~mak" + LF
        + "Disallow: /" + CRLF;

        SimpleRobotRules rules;

        rules = createRobotRules("WebCrawler/3.0", mixedEndingsRobotsTxt.getBytes());
        Assert.assertTrue(rules.isAllowed("http://www.fict.org/"));
        Assert.assertTrue(rules.isAllowed("http://www.fict.org/index.html"));

        rules = createRobotRules("Unknown/1.0", mixedEndingsRobotsTxt.getBytes());
        Assert.assertFalse(rules.isAllowed("http://www.fict.org/"));
        Assert.assertFalse(rules.isAllowed("http://www.fict.org/index.html"));
        Assert.assertTrue(rules.isAllowed("http://www.fict.org/robots.txt"));
        Assert.assertTrue(rules.isAllowed("http://www.fict.org/server.html"));
        Assert.assertTrue(rules.isAllowed("http://www.fict.org/services/fast.html"));
        Assert.assertTrue(rules.isAllowed("http://www.fict.org/services/slow.html"));
        Assert.assertFalse(rules.isAllowed("http://www.fict.org/orgo.gif"));
        Assert.assertTrue(rules.isAllowed("http://www.fict.org/org/about.html"));
        Assert.assertFalse(rules.isAllowed("http://www.fict.org/org/plans.html"));
        Assert.assertFalse(rules.isAllowed("http://www.fict.org/%7Ejim/jim.html"));
        Assert.assertTrue(rules.isAllowed("http://www.fict.org/%7Emak/mak.html"));

    }
    
    @Test
    public void testRfpCases() throws MalformedURLException {
        // Run through all of the tests that are part of the robots.txt RFP
        // http://www.robotstxt.org/norobots-rfc.txt
        final String rfpExampleRobotsTxt = "# /robots.txt for http://www.fict.org/" + CRLF
        + "# comments to webmaster@fict.org" + CRLF
        + CRLF
        + "User-agent: unhipbot" + CRLF
        + "Disallow: /" + CRLF
        + "" + CRLF
        + "User-agent: webcrawler" + CRLF
        + "User-agent: excite" + CRLF
        + "Disallow: " + CRLF
        + CRLF
        + "User-agent: *" + CRLF
        + "Disallow: /org/plans.html" + CRLF
        + "Allow: /org/" + CRLF
        + "Allow: /serv" + CRLF
        + "Allow: /~mak" + CRLF
        + "Disallow: /" + CRLF;
        
        SimpleRobotRules rules;
        
        rules = createRobotRules("UnhipBot/0.1", rfpExampleRobotsTxt.getBytes());
        Assert.assertFalse(rules.isAllowed("http://www.fict.org/"));
        Assert.assertFalse(rules.isAllowed("http://www.fict.org/index.html"));
        Assert.assertTrue(rules.isAllowed("http://www.fict.org/robots.txt"));
        Assert.assertFalse(rules.isAllowed("http://www.fict.org/server.html"));
        Assert.assertFalse(rules.isAllowed("http://www.fict.org/services/fast.html"));
        Assert.assertFalse(rules.isAllowed("http://www.fict.org/services/slow.html"));
        Assert.assertFalse(rules.isAllowed("http://www.fict.org/orgo.gif"));
        Assert.assertFalse(rules.isAllowed("http://www.fict.org/org/about.html"));
        Assert.assertFalse(rules.isAllowed("http://www.fict.org/org/plans.html"));
        Assert.assertFalse(rules.isAllowed("http://www.fict.org/%7Ejim/jim.html"));
        Assert.assertFalse(rules.isAllowed("http://www.fict.org/%7Emak/mak.html"));

        rules = createRobotRules("WebCrawler/3.0", rfpExampleRobotsTxt.getBytes());
        Assert.assertTrue(rules.isAllowed("http://www.fict.org/"));
        Assert.assertTrue(rules.isAllowed("http://www.fict.org/index.html"));
        Assert.assertTrue(rules.isAllowed("http://www.fict.org/robots.txt"));
        Assert.assertTrue(rules.isAllowed("http://www.fict.org/server.html"));
        Assert.assertTrue(rules.isAllowed("http://www.fict.org/services/fast.html"));
        Assert.assertTrue(rules.isAllowed("http://www.fict.org/services/slow.html"));
        Assert.assertTrue(rules.isAllowed("http://www.fict.org/orgo.gif"));
        Assert.assertTrue(rules.isAllowed("http://www.fict.org/org/about.html"));
        Assert.assertTrue(rules.isAllowed("http://www.fict.org/org/plans.html"));
        Assert.assertTrue(rules.isAllowed("http://www.fict.org/%7Ejim/jim.html"));
        Assert.assertTrue(rules.isAllowed("http://www.fict.org/%7Emak/mak.html"));

        rules = createRobotRules("Excite/1.0", rfpExampleRobotsTxt.getBytes());
        Assert.assertTrue(rules.isAllowed("http://www.fict.org/"));
        Assert.assertTrue(rules.isAllowed("http://www.fict.org/index.html"));
        Assert.assertTrue(rules.isAllowed("http://www.fict.org/robots.txt"));
        Assert.assertTrue(rules.isAllowed("http://www.fict.org/server.html"));
        Assert.assertTrue(rules.isAllowed("http://www.fict.org/services/fast.html"));
        Assert.assertTrue(rules.isAllowed("http://www.fict.org/services/slow.html"));
        Assert.assertTrue(rules.isAllowed("http://www.fict.org/orgo.gif"));
        Assert.assertTrue(rules.isAllowed("http://www.fict.org/org/about.html"));
        Assert.assertTrue(rules.isAllowed("http://www.fict.org/org/plans.html"));
        Assert.assertTrue(rules.isAllowed("http://www.fict.org/%7Ejim/jim.html"));
        Assert.assertTrue(rules.isAllowed("http://www.fict.org/%7Emak/mak.html"));

        rules = createRobotRules("Unknown/1.0", rfpExampleRobotsTxt.getBytes());
        Assert.assertFalse(rules.isAllowed("http://www.fict.org/"));
        Assert.assertFalse(rules.isAllowed("http://www.fict.org/index.html"));
        Assert.assertTrue(rules.isAllowed("http://www.fict.org/robots.txt"));
        Assert.assertTrue(rules.isAllowed("http://www.fict.org/server.html"));
        Assert.assertTrue(rules.isAllowed("http://www.fict.org/services/fast.html"));
        Assert.assertTrue(rules.isAllowed("http://www.fict.org/services/slow.html"));
        Assert.assertFalse(rules.isAllowed("http://www.fict.org/orgo.gif"));
        Assert.assertTrue(rules.isAllowed("http://www.fict.org/org/about.html"));
        Assert.assertFalse(rules.isAllowed("http://www.fict.org/org/plans.html"));
        Assert.assertFalse(rules.isAllowed("http://www.fict.org/%7Ejim/jim.html"));
        Assert.assertTrue(rules.isAllowed("http://www.fict.org/%7Emak/mak.html"));
    }

    @Test
    public void testNutchCases() throws MalformedURLException {
        // Run through the Nutch test cases.
        
        final String nutchRobotsTxt = "User-Agent: Agent1 #foo" + CR 
        + "Disallow: /a" + CR 
        + "Disallow: /b/a" + CR 
        + "#Disallow: /c" + CR 
        + "" + CR 
        + "" + CR 
        + "User-Agent: Agent2 Agent3#foo" + CR 
        + "User-Agent: Agent4" + CR 
        + "Disallow: /d" + CR 
        + "Disallow: /e/d/" + CR
        + "" + CR 
        + "User-Agent: *" + CR 
        + "Disallow: /foo/bar/" + CR;

        SimpleRobotRules rules;
        
        rules = createRobotRules("Agent1", nutchRobotsTxt.getBytes());
        Assert.assertFalse(rules.isAllowed("http://www.domain.com/a"));
        Assert.assertFalse(rules.isAllowed("http://www.domain.com/a/"));
        Assert.assertFalse(rules.isAllowed("http://www.domain.com/a/bloh/foo.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/b"));
        Assert.assertFalse(rules.isAllowed("http://www.domain.com/b/a"));
        Assert.assertFalse(rules.isAllowed("http://www.domain.com/b/a/index.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/b/b/foo.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/c"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/c/a"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/c/a/index.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/c/b/foo.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/d"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/d/a"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/e/a/index.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/e/d"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/e/d/foo.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/e/doh.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/f/index.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/foo/bar/baz.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/f/"));

        rules = createRobotRules("Agent2", nutchRobotsTxt.getBytes());
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/a"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/a/"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/a/bloh/foo.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/b"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/b/a"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/b/a/index.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/b/b/foo.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/c"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/c/a"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/c/a/index.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/c/b/foo.html"));
        Assert.assertFalse(rules.isAllowed("http://www.domain.com/d"));
        Assert.assertFalse(rules.isAllowed("http://www.domain.com/d/a"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/e/a/index.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/e/d"));
        Assert.assertFalse(rules.isAllowed("http://www.domain.com/e/d/foo.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/e/doh.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/f/index.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/foo/bar/baz.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/f/"));

        rules = createRobotRules("Agent3", nutchRobotsTxt.getBytes());
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/a"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/a/"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/a/bloh/foo.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/b"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/b/a"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/b/a/index.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/b/b/foo.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/c"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/c/a"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/c/a/index.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/c/b/foo.html"));
        Assert.assertFalse(rules.isAllowed("http://www.domain.com/d"));
        Assert.assertFalse(rules.isAllowed("http://www.domain.com/d/a"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/e/a/index.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/e/d"));
        Assert.assertFalse(rules.isAllowed("http://www.domain.com/e/d/foo.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/e/doh.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/f/index.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/foo/bar/baz.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/f/"));

        rules = createRobotRules("Agent4", nutchRobotsTxt.getBytes());
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/a"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/a/"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/a/bloh/foo.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/b"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/b/a"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/b/a/index.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/b/b/foo.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/c"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/c/a"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/c/a/index.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/c/b/foo.html"));
        Assert.assertFalse(rules.isAllowed("http://www.domain.com/d"));
        Assert.assertFalse(rules.isAllowed("http://www.domain.com/d/a"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/e/a/index.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/e/d"));
        Assert.assertFalse(rules.isAllowed("http://www.domain.com/e/d/foo.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/e/doh.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/f/index.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/foo/bar/baz.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/f/"));

        rules = createRobotRules("Agent5", nutchRobotsTxt.getBytes());
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/a"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/a/"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/a/bloh/foo.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/b"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/b/a"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/b/a/index.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/b/b/foo.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/c"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/c/a"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/c/a/index.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/c/b/foo.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/d"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/d/a"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/e/a/index.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/e/d"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/e/d/foo.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/e/doh.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/f/index.html"));
        Assert.assertFalse(rules.isAllowed("http://www.domain.com/foo/bar/baz.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/f/"));
    }
    
    
    @Test
    public void testHtmlMarkupInRobotsTxt() throws MalformedURLException {
        final String htmlRobotsTxt = "<!DOCTYPE HTML PUBLIC \"-//W3C//DTD HTML 3.2 Final//EN\"><HTML>\n"
            +"<HEAD>\n"
            +"<TITLE>/robots.txt</TITLE>\n"
            +"</HEAD>\n"
            +"<BODY>\n"
            +"User-agent: anybot<BR>\n"
            +"Disallow: <BR>\n"
            +"Crawl-Delay: 10<BR>\n"
            +"\n"
            +"User-agent: *<BR>\n"
            +"Disallow: /<BR>\n"
            +"Crawl-Delay: 30<BR>\n"
            +"\n"
            +"</BODY>\n"
            +"</HTML>\n";

        SimpleRobotRules rules;
        
        rules = createRobotRules("anybot", htmlRobotsTxt.getBytes());
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/index.html"));
        Assert.assertEquals(10000, rules.getCrawlDelay());

        rules = createRobotRules("bogusbot", htmlRobotsTxt.getBytes());
        Assert.assertFalse(rules.isAllowed("http://www.domain.com/index.html"));
        Assert.assertEquals(30000, rules.getCrawlDelay());
    }
    
    @Test
    public void testIgnoreOfHtml() throws MalformedURLException, UnsupportedEncodingException {
        final String htmlFile = "<HTML><HEAD><TITLE>Site under Maintenance</TITLE></HTML>";
        
        SimpleRobotRules rules = createRobotRules("anybot", htmlFile.getBytes("us-ascii"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/"));
        Assert.assertFalse(rules.getDeferVisits());
    }

    @Test
    public void testHeritrixCases() throws MalformedURLException {
        final String heritrixRobotsTxt = "User-agent: *\n" +
                "Disallow: /cgi-bin/\n" +
                "Disallow: /details/software\n" +
                "\n"+
                "User-agent: denybot\n" +
                "Disallow: /\n" +
                "\n"+
                "User-agent: allowbot1\n" +
                "Disallow: \n" +
                "\n"+
                "User-agent: allowbot2\n" +
                "Disallow: /foo\n" +
                "Allow: /\n"+
                "\n"+
                "User-agent: delaybot\n" +
                "Disallow: /\n" +
                "Crawl-Delay: 20\n"+
                "Allow: /images/\n";
        
        SimpleRobotRules rules;
        rules = createRobotRules("Mozilla allowbot1 99.9", heritrixRobotsTxt.getBytes());
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/path"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/"));

        rules = createRobotRules("Mozilla allowbot2 99.9", heritrixRobotsTxt.getBytes());
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/path"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/"));
        Assert.assertFalse(rules.isAllowed("http://www.domain.com/foo"));

        rules = createRobotRules("Mozilla denybot 99.9", heritrixRobotsTxt.getBytes());
        Assert.assertFalse(rules.isAllowed("http://www.domain.com/path"));
        Assert.assertFalse(rules.isAllowed("http://www.domain.com/"));
        Assert.assertEquals(IRobotRules.UNSET_CRAWL_DELAY, rules.getCrawlDelay());
        
        rules = createRobotRules("Mozilla anonbot 99.9", heritrixRobotsTxt.getBytes());
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/path"));
        Assert.assertFalse(rules.isAllowed("http://www.domain.com/cgi-bin/foo.pl"));

        rules = createRobotRules("Mozilla delaybot 99.9", heritrixRobotsTxt.getBytes());
        Assert.assertEquals(20000, rules.getCrawlDelay());
    }

    
    @Test
    public void testCaseSensitivePaths() throws MalformedURLException {
        final String simpleRobotsTxt = "User-agent: *" + CRLF
        + "Allow: /AnyPage.html" + CRLF
        + "Allow: /somepage.html" + CRLF
        + "Disallow: /";

        SimpleRobotRules rules = createRobotRules("Any-darn-crawler", simpleRobotsTxt.getBytes());
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/anypage.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/SomePage.html"));
    }
    
    
    @Test
    public void testEmptyDisallow() throws MalformedURLException {
        final String simpleRobotsTxt = "User-agent: *" + CRLF
        + "Disallow:";

        SimpleRobotRules rules = createRobotRules("Any-darn-crawler", simpleRobotsTxt.getBytes());
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/anypage.html"));
    }
    
    
    @Test
    public void testEmptyAllow() throws MalformedURLException {
        final String simpleRobotsTxt = "User-agent: *" + CRLF
        + "Allow:";

        SimpleRobotRules rules = createRobotRules("Any-darn-crawler", simpleRobotsTxt.getBytes());
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/anypage.html"));
    }
    
    
    @Test
    public void testMultiWildcard() throws MalformedURLException {
        // Make sure we only take the first wildcard entry.
        final String simpleRobotsTxt = "User-agent: *" + CRLF
        + "Disallow: /index.html" + CRLF
        + "Allow: /" + CRLF
        + CRLF
        + "User-agent: *" + CRLF
        + "Disallow: /";

        SimpleRobotRules rules = createRobotRules("Any-darn-crawler", simpleRobotsTxt.getBytes());
        Assert.assertFalse(rules.isAllowed("http://www.domain.com/index.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/anypage.html"));
    }
    
    
    @Test
    public void testMultiMatches() throws MalformedURLException {
        // Make sure we only take the first record that matches.
        final String simpleRobotsTxt = "User-agent: darn-crawler" + CRLF
        + "Disallow: /index.html" + CRLF
        + "Allow: /" + CRLF
        + CRLF
        + "User-agent: crawler" + CRLF
        + "Disallow: /";

        SimpleRobotRules rules = createRobotRules("Any-darn-crawler", simpleRobotsTxt.getBytes());
        Assert.assertFalse(rules.isAllowed("http://www.domain.com/index.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/anypage.html"));
    }
    
    @Test
    public void testMultiAgentNames() throws MalformedURLException {
        // When there are more than one agent name on a line.
        final String simpleRobotsTxt = "User-agent: crawler1 crawler2" + CRLF
        + "Disallow: /index.html" + CRLF
        + "Allow: /";

        SimpleRobotRules rules = createRobotRules("crawler2", simpleRobotsTxt.getBytes());
        Assert.assertFalse(rules.isAllowed("http://www.domain.com/index.html"));
        Assert.assertTrue(rules.isAllowed("http://www.domain.com/anypage.html"));
    }
    
    @Test
    public void testUnsupportedFields() throws MalformedURLException {
        // When we have a new field type that we don't know about.
        final String simpleRobotsTxt = "User-agent: crawler1" + CRLF
        + "Disallow: /index.html" + CRLF
        + "Allow: /" + CRLF
        + "newfield: 234" + CRLF
        + "User-agent: crawler2" + CRLF
        + "Disallow: /";

        SimpleRobotRules rules = createRobotRules("crawler2", simpleRobotsTxt.getBytes());
        Assert.assertFalse(rules.isAllowed("http://www.domain.com/anypage.html"));
    }
    
    @Test
    public void testAcapFields() throws MalformedURLException {
        final String robotsTxt = "acap-crawler: *" + CRLF
        + "acap-disallow-crawl: /ultima_ora/";

        SimpleRobotRules rules = createRobotRules("foobot", robotsTxt.getBytes());
        Assert.assertEquals(0, rules.getNumWarnings());
    }
    
    @Test
    public void testStatusCodeCreation() throws MalformedURLException {
        SimpleRobotRules rules;
        
        rules = new SimpleRobotRules(FAKE_ROBOTS_URL, HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        Assert.assertTrue(rules.getDeferVisits());
        Assert.assertFalse(rules.isAllowed("http://www.domain.com/index.html"));
        
        rules = new SimpleRobotRules(FAKE_ROBOTS_URL, HttpServletResponse.SC_MOVED_PERMANENTLY);
        Assert.assertTrue(rules.getDeferVisits());
        Assert.assertFalse(rules.isAllowed("http://www.domain.com/index.html"));
        
        // All 4xx status codes should result in open access (ala Google)
        // SC_FORBIDDEN
        // SC_NOT_FOUND
        // SC_GONE
        for (int status = 400; status < 420; status++) {
            rules = new SimpleRobotRules(FAKE_ROBOTS_URL, status);
            Assert.assertFalse(rules.getDeferVisits());
            Assert.assertTrue(rules.isAllowed("http://www.domain.com/index.html"));
        }
    }
    
    @Test
    public void testCrawlDelay() {
        final String delayRules1RobotsTxt = "User-agent: bixo" + CR +
                            "Crawl-delay: 10" + CR +
                            "User-agent: foobot" + CR +
                            "Crawl-delay: 20" + CR +
                            "User-agent: *" + CR + 
                            "Disallow:/baz" + CR;
        
        SimpleRobotRules rules = createRobotRules("bixo", delayRules1RobotsTxt.getBytes());
        long crawlDelay = rules.getCrawlDelay();
        Assert.assertEquals("testing crawl delay for agent bixo - rule 1", 10000, crawlDelay);
        
        final String delayRules2RobotsTxt = "User-agent: foobot" + CR +
                            "Crawl-delay: 20" + CR +
                            "User-agent: *" + CR + 
                            "Disallow:/baz" + CR;
        
        rules = createRobotRules("bixo", delayRules2RobotsTxt.getBytes());
        crawlDelay = rules.getCrawlDelay();
        Assert.assertEquals("testing crawl delay for agent bixo - rule 2", SimpleRobotRules.UNSET_CRAWL_DELAY, crawlDelay);
      }

    @Test
    public void testBigCrawlDelay() throws MalformedURLException {
        final String robotsTxt = "User-agent: *" + CR +
        "Crawl-delay: 3600" + CR +
        "Disallow:" + CR;

        SimpleRobotRules rules = createRobotRules("bixo", robotsTxt.getBytes());
        Assert.assertFalse("disallow all if huge crawl delay", rules.isAllowed("http://www.domain.com/"));
    }
    
    @Test
    public void testBrokenKrugleRobotsTxtFile() throws MalformedURLException {
        final String krugleRobotsTxt = "User-agent: *" + CR
            + "Disallow: /maintenance.html" + CR
            + "Disallow: /perl/" + CR
            + "Disallow: /cgi-bin/" + CR
            + "Disallow: /examples/" + CR
            + "Crawl-delay: 3" + CR
            + "" + CR
            + "User-agent: googlebot" + CR
            + "Crawl-delay: 1" + CR
            + "" + CR
            + "User-agent: qihoobot" + CR
            + "Disallow: /";
        
        SimpleRobotRules rules = createRobotRules("googlebot/2.1", krugleRobotsTxt.getBytes());
        Assert.assertTrue(rules.isAllowed("http://www.krugle.com/examples/index.html"));
    }
    
    @Test
    public void testRobotsWithBOM() throws IOException {
        byte[] bigBuffer = new byte[8096];
        InputStream is = SimpleRobotRulesTest.class.getResourceAsStream("/robots-with-bom.txt");
        int len = is.read(bigBuffer);
        byte[] robotsData = Arrays.copyOf(bigBuffer, len);
        
        SimpleRobotRules rules = createRobotRules("foobot", robotsData);
        Assert.assertFalse("Disallow match against *", rules.isAllowed("http://www.domain.com/profile"));
    }
    
    @Test
    public void testFloatingPointCrawlDelay() throws MalformedURLException {
        final String robotsTxt = "User-agent: *" + CR +
        "Crawl-delay: 0.5" + CR +
        "Disallow:" + CR;

        SimpleRobotRules rules = createRobotRules("bixo", robotsTxt.getBytes());
        Assert.assertEquals(500, rules.getCrawlDelay());
    }
}
