/*
 * Copyright (c) 1997-2009 101tec Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy 
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights 
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell 
 * copies of the Software, and to permit persons to whom the Software is 
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in 
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR 
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, 
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE 
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER 
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 */
package bixo.tools;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.LineIterator;
import org.apache.hadoop.mapred.JobConf;

import bixo.cascading.BixoFlowProcess;
import bixo.config.FetcherPolicy;
import bixo.config.QueuePolicy;
import bixo.config.UserAgent;
import bixo.datum.ScoredUrlDatum;
import bixo.datum.UrlStatus;
import bixo.fetcher.FetcherManager;
import bixo.fetcher.FetcherQueue;
import bixo.fetcher.FetcherQueueMgr;
import bixo.fetcher.http.SimpleHttpFetcher;
import bixo.utils.DomainNames;
import cascading.scheme.SequenceFile;
import cascading.tap.Lfs;
import cascading.tuple.Fields;
import cascading.tuple.TupleEntryCollector;

public class RunTestFetcher {
    private static final long TEN_MINUTES = 10 * 60 * 1000L;
    
    /**
     * @param args
     */
    public static void main(String[] args) {
        try {
            LineIterator iter = FileUtils.lineIterator(new File(args[0]), "UTF-8");

            HashMap<String, List<String>> domainMap = new HashMap<String, List<String>>();

            while (iter.hasNext()) {
                String line = iter.nextLine();

                try {
                    URL url = new URL(line);
                    String pld = DomainNames.getPLD(url);
                    List<String> urls = domainMap.get(pld);
                    if (urls == null) {
                        urls = new ArrayList<String>();
                        domainMap.put(pld, urls);
                    }

                    urls.add(url.toExternalForm());
                } catch (MalformedURLException e) {
                    System.out.println("Invalid URL in input file: " + line);
                }
            }

            // Now we have the URLs, so create queues for processing.
            System.out.println("Unique PLDs: " + domainMap.size());

            // setup output
            JobConf conf = new JobConf();
            String out = "build/test/RunTestFetcher/working";
            TupleEntryCollector tupleEntryCollector = new Lfs(new SequenceFile(Fields.ALL), out, true).openForWrite(conf);

            BixoFlowProcess process = new BixoFlowProcess();
            FetcherPolicy defaultPolicy = new FetcherPolicy();
            defaultPolicy.setCrawlEndTime(System.currentTimeMillis() + TEN_MINUTES);
            QueuePolicy queuePolicy = new QueuePolicy(FetcherQueueMgr.DEFAULT_MAX_URLS_IN_MEMORY, defaultPolicy);
            FetcherQueueMgr queueMgr = new FetcherQueueMgr(process, defaultPolicy, queuePolicy);
            
            for (String pld : domainMap.keySet()) {
                FetcherQueue queue = queueMgr.createQueue(pld, tupleEntryCollector, 30);
                List<String> urls = domainMap.get(pld);
                System.out.println("Adding " + urls.size() + " URLs for " + pld);
                for (String url : urls) {
                    ScoredUrlDatum urlScore = new ScoredUrlDatum(url, 0, 0, UrlStatus.UNFETCHED, null, 0.5d, null);
                    queue.offer(urlScore);
                }

                queueMgr.offer(queue);
            }

            UserAgent userAgent = new UserAgent("bixo test fetcher", "bixo-dev@groups.yahoo.com", "http://wiki.github.com/bixo/bixo/bixocrawler");
            FetcherManager threadMgr = new FetcherManager(queueMgr, new SimpleHttpFetcher(10, userAgent), new BixoFlowProcess());
            Thread t = new Thread(threadMgr);
            t.setName("Fetcher manager");
            t.start();

            // We have a bunch of pages to "fetch". Spin until we're done.
            while (!threadMgr.isDone()) {
            }
            t.interrupt();
        } catch (Throwable t) {
            System.err.println("Exception: " + t.getMessage());
            t.printStackTrace(System.err);
            System.exit(-1);
        }
    }

}
