package com.transpac.helpful.operations;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.Logger;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.html.HtmlParser;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import bixo.cascading.NullContext;
import bixo.datum.FetchedDatum;
import bixo.datum.UrlDatum;
import bixo.datum.UrlStatus;
import cascading.flow.FlowProcess;
import cascading.operation.BaseOperation;
import cascading.operation.Function;
import cascading.operation.FunctionCall;
import cascading.tuple.Fields;
import cascading.tuple.TupleEntry;

@SuppressWarnings("serial")
public class ParseModMboxPageFunction extends BaseOperation<NullContext> implements Function<NullContext> {
	private static final Logger LOGGER = Logger.getLogger(ParseModMboxPageFunction.class);
	
	private Fields _metaDataFields = new Fields();
	private List<String> _ids = new ArrayList<String>();
	
	// These aren't easily serializable, so make them transient and do lazy
	// creation from the operate() call.
	private transient HtmlParser _parser;
	private transient ContentHandler _handler;
	
	public ParseModMboxPageFunction(Fields metaDataFields) {
		super(UrlDatum.FIELDS.append(metaDataFields));
		_metaDataFields = metaDataFields;
	}

	private synchronized void init() {
		if (_parser == null) {
			_parser = new HtmlParser();
		}
		
		if (_handler == null) {
			_handler = new DefaultHandler() {

				@Override
				public void startDocument() throws SAXException {
					super.startDocument();
					_ids.clear();
				}

				@Override
				public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
					// We're looking for something like
					// <a href="200606.mbox/thread">
					if (qName.equalsIgnoreCase("a") && (attributes.getValue("href").endsWith("/thread"))) {
						_ids.add(attributes.getValue("href").substring(0, 6));
					}
				}
			};
		}
	}
	
	@Override
	public void operate(FlowProcess process, FunctionCall<NullContext> functionCall) {
        TupleEntry arguments = functionCall.getArguments();
        FetchedDatum fetchedDatum = new FetchedDatum(arguments.getTuple(), _metaDataFields);

        if (fetchedDatum.getContentType().startsWith("text/html")) {
        	init();

        	Metadata metadata = new Metadata();
        	InputStream is = new ByteArrayInputStream(fetchedDatum.getContentBytes());
        	
        	try {
        		_parser.parse(is, _handler, metadata, new ParseContext());

        		// _ids now has a list of the mailbox IDs that we use to create URLs.
        		for (String id : _ids) {
        			String url = String.format("%s/%s.mbox", fetchedDatum.getBaseUrl(), id);
        			UrlDatum datum = new UrlDatum(url, 0, 0, UrlStatus.UNFETCHED, fetchedDatum.getMetaDataMap());
        			functionCall.getOutputCollector().add(datum.toTuple());
        		}
        	} catch (Exception e) {
				LOGGER.error("Exception parsing mod_mbox page", e);
			}
        }
	}
}
