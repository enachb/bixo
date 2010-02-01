package bixo.operations;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

import org.apache.log4j.Logger;

import bixo.cascading.BixoFlowProcess;
import bixo.cascading.LoggingFlowReporter;
import bixo.cascading.NullContext;
import bixo.datum.BaseDatum;
import bixo.datum.FetchedDatum;
import bixo.datum.PreFetchedDatum;
import bixo.datum.ScoredUrlDatum;
import bixo.datum.UrlStatus;
import bixo.fetcher.FetchTask;
import bixo.fetcher.IFetchMgr;
import bixo.fetcher.http.IHttpFetcher;
import bixo.hadoop.FetchCounters;
import bixo.utils.GroupingKey;
import bixo.utils.ThreadedExecutor;
import cascading.flow.FlowProcess;
import cascading.flow.hadoop.HadoopFlowProcess;
import cascading.operation.BaseOperation;
import cascading.operation.Buffer;
import cascading.operation.BufferCall;
import cascading.operation.OperationCall;
import cascading.tuple.Fields;
import cascading.tuple.Tuple;
import cascading.tuple.TupleEntry;
import cascading.tuple.TupleEntryCollector;

@SuppressWarnings( { "serial", "unchecked" })
public class FetchBuffer extends BaseOperation<NullContext> implements Buffer<NullContext>, IFetchMgr {
    private static Logger LOGGER = Logger.getLogger(FetchBuffer.class);

    private static final Fields FETCH_RESULT_FIELD = new Fields(BaseDatum.fieldName(FetchBuffer.class, "fetch-exception"));

    // How long to wait before a fetch request gets rejected.
    private static final long REQUEST_TIMEOUT = 10 * 1000L;
    
    // How long to wait before doing a hard termination.
    private static final long TERMINATION_TIMEOUT = 100 * 1000L;

    private IHttpFetcher _fetcher;
    private long _crawlEndTime;
    private final Fields _metaDataFields;

    private transient ThreadedExecutor _executor;
    private transient BixoFlowProcess _flowProcess;
    private transient TupleEntryCollector _collector;

    private transient Object _refLock;
    private transient ConcurrentHashMap<String, Long> _activeRefs;
    private transient ConcurrentHashMap<String, Long> _pendingRefs;

    public FetchBuffer(IHttpFetcher fetcher, long crawlEndTime, Fields metaDataFields) {
        // We're going to output a tuple that contains a FetchedDatum, plus meta-data,
        // plus a result that could be a string, a status, or an exception
        super(FetchedDatum.FIELDS.append(metaDataFields).append(FETCH_RESULT_FIELD));

        _fetcher = fetcher;
        _crawlEndTime = crawlEndTime;
        _metaDataFields = metaDataFields;
    }

    @Override
    public boolean isSafe() {
        // We definitely DO NOT want to be called multiple times for the same
        // scored datum, so let Cascading 1.1 know that the output from us should
        // be stashed in tempHfs if need be.
        return false;
    }

    @Override
    public void prepare(FlowProcess flowProcess, OperationCall operationCall) {
        super.prepare(flowProcess, operationCall);

        // FUTURE KKr - use Cascading process vs creating our own, once it
        // supports logging in local mode, and a setStatus() call.
        // FUTURE KKr - check for a serialized external reporter in the process,
        // add it if it exists.
        _flowProcess = new BixoFlowProcess((HadoopFlowProcess) flowProcess);
        _flowProcess.addReporter(new LoggingFlowReporter());

        _executor = new ThreadedExecutor(_fetcher.getMaxThreads(), REQUEST_TIMEOUT);

        _refLock = new Object();
        _pendingRefs = new ConcurrentHashMap<String, Long>();
        _activeRefs = new ConcurrentHashMap<String, Long>();
    }

    @Override
    public void operate(FlowProcess process, BufferCall<NullContext> buffCall) {
        Iterator<TupleEntry> values = buffCall.getArgumentsIterator();
        _collector = buffCall.getOutputCollector();

        // Each value is a PreFetchedDatum that contains a set of URLs, plus other values
        // needed to set state properly.
        while (values.hasNext()) {
            PreFetchedDatum datum = new PreFetchedDatum(values.next().getTuple(), _metaDataFields);
            List<ScoredUrlDatum> urls = datum.getUrls();
            String ref = datum.getGroupingKey().getRef();

            // First see if we're past the target crawl end time. If so, skip these URLs
            if (System.currentTimeMillis() > _crawlEndTime) {
                skipUrls(urls, UrlStatus.SKIPPED_TIME_LIMIT, "Time limit skipped %d URLs");
                continue;
            }
            
            // Next see if we're still fetching from the same server. If so, then abort these URLs
            Long nextFetchTime = _activeRefs.get(ref);
            if (nextFetchTime != null) {
                skipUrls(urls, GroupingKey.DEFERRED_GROUPING_KEY);
                continue;
            }

            // See if we already have fetched URLs from this ref (IP-crawl delay). If so, then we
            // need to figure out how long we should wait.
            nextFetchTime = _pendingRefs.get(ref);
            if ((nextFetchTime != null) && (nextFetchTime > System.currentTimeMillis())) {
                // We have to wait until it's time to fetch.
                long delta = nextFetchTime - System.currentTimeMillis();
                LOGGER.trace(String.format("Waiting %dms until we can fetch from %s", delta, ref));

                try {
                    Thread.sleep(delta);
                } catch (InterruptedException e) {
                    LOGGER.warn("FetchBuffer interrupted!");
                    Thread.currentThread().interrupt();
                    continue;
                }
            }

            // Figure out the correct time for the fetch after this one.
            if (datum.isLastList()) {
                nextFetchTime = 0L;
            } else {
                nextFetchTime = System.currentTimeMillis() + datum.getFetchDelay();
            }
            
            // Now we can finally do the fetch
            try {
                Runnable doFetch = new FetchTask(this, _fetcher, urls, ref);
                makeActive(ref, nextFetchTime);
                _executor.execute(doFetch);
                
                _flowProcess.increment(FetchCounters.URLS_QUEUED, urls.size());
                _flowProcess.increment(FetchCounters.URLS_REMAINING, urls.size());
            } catch (RejectedExecutionException e) {
                // should never happen.
                LOGGER.error("Fetch pool rejected our fetch list for " + ref);
                
                finished(ref);
                
                _flowProcess.increment(FetchCounters.URLS_SKIPPED, urls.size());
                _flowProcess.decrement(FetchCounters.URLS_REMAINING, urls.size());
                skipUrls(urls, GroupingKey.DEFERRED_GROUPING_KEY);
            }
        }
    }

    @Override
    public void cleanup(FlowProcess process, OperationCall operationCall) {
        try {
            if (!_executor.terminate(TERMINATION_TIMEOUT)) {
                LOGGER.warn("Had to do a hard termination of general fetching");
            }
        } catch (InterruptedException e) {
            // FUTURE What's the right thing to do here? E.g. do I need to worry about
            // losing URLs still to be processed?
            LOGGER.warn("Interrupted while waiting for termination");
        }

        _flowProcess.dumpCounters();
    }

    private void skipUrls(List<ScoredUrlDatum> urls, String key) {
        String traceMsg = null;

        // TODO KKr - move to GroupingKey as static methods that return a trace msg (with %d param)
        // E.g. makeTraceMsg(key) => String
        if (key.equals(GroupingKey.BLOCKED_GROUPING_KEY)) {
            traceMsg = "Blocked %d URLs";
        } else if (key.equals(GroupingKey.UNKNOWN_HOST_GROUPING_KEY)) {
            traceMsg = "Host not found for %d URLs";
        } else if (key.equals(GroupingKey.INVALID_URL_GROUPING_KEY)) {
            traceMsg = "Invalid format for %d URLs";
        } else if (key.equals(GroupingKey.DEFERRED_GROUPING_KEY)) {
            traceMsg = "Robots.txt problems deferred processing of %d URLs";
        } else if (key.equals(GroupingKey.SKIPPED_GROUPING_KEY)) {
            traceMsg = "Scoring explicitly skipping %d URLs";
        } else {
            throw new RuntimeException("Unknown value for special grouping key: " + key);
        }

        UrlStatus status = GroupingKey.makeUrlStatusFromKey(key);
        skipUrls(urls, status, traceMsg);
    }

    private void skipUrls(List<ScoredUrlDatum> urls, UrlStatus status, String traceMsg) {
        for (ScoredUrlDatum datum : urls) {
            FetchedDatum result = new FetchedDatum(datum);
            Tuple tuple = result.toTuple();
            tuple.add(status.toString());
            _collector.add(tuple);
        }

        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace(String.format(traceMsg, urls.size()));
        }
    }
    
    /**
     * Make <ref> active, removing from pending if necessary.
     * 
     * @param ref
     * @param nextFetchTime
     */
    private void makeActive(String ref, Long nextFetchTime) {
        synchronized (_refLock) {
            _pendingRefs.remove(ref);
            _activeRefs.put(ref, nextFetchTime);
        }
    }

    @Override
    public void finished(String ref) {
        synchronized (_refLock) {
            Long nextFetchTime = _activeRefs.get(ref);
            if (nextFetchTime == null) {
                throw new RuntimeException("finished called on non-active ref: " + ref);
            }
            
            // If there's going to be more to fetch, put it back in the pending pool.
            if (nextFetchTime != 0) {
                _pendingRefs.put(ref, nextFetchTime);
            }
        }
    }

    @Override
    public TupleEntryCollector getCollector() {
        return _collector;
    }

    @Override
    public BixoFlowProcess getProcess() {
        return _flowProcess;
    }
}
