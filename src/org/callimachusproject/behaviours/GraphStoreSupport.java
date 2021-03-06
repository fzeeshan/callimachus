package org.callimachusproject.behaviours;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.http.Consts;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpVersion;
import org.apache.http.StatusLine;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.util.EntityUtils;
import org.callimachusproject.annotations.requires;
import org.callimachusproject.engine.events.Drop;
import org.callimachusproject.engine.events.Graph;
import org.callimachusproject.engine.events.InsertData;
import org.callimachusproject.engine.events.Namespace;
import org.callimachusproject.engine.events.Triple;
import org.callimachusproject.engine.helpers.SPARQLWriter;
import org.callimachusproject.engine.helpers.SparqlUpdateFactory;
import org.callimachusproject.engine.model.AbsoluteTermFactory;
import org.callimachusproject.engine.model.IRI;
import org.callimachusproject.engine.model.Node;
import org.callimachusproject.engine.model.Term;
import org.callimachusproject.io.BoundedDescription;
import org.callimachusproject.util.PercentCodec;
import org.openrdf.OpenRDFException;
import org.openrdf.annotations.Header;
import org.openrdf.annotations.HeaderParam;
import org.openrdf.annotations.Method;
import org.openrdf.annotations.Param;
import org.openrdf.annotations.Path;
import org.openrdf.annotations.Sparql;
import org.openrdf.annotations.Type;
import org.openrdf.http.object.client.HttpUriEntity;
import org.openrdf.http.object.client.HttpUriResponse;
import org.openrdf.http.object.client.StreamingHttpEntity;
import org.openrdf.http.object.exceptions.BadRequest;
import org.openrdf.http.object.exceptions.MethodNotAllowed;
import org.openrdf.http.object.exceptions.NotFound;
import org.openrdf.http.object.exceptions.PreconditionRequired;
import org.openrdf.http.object.fluid.FluidType;
import org.openrdf.http.object.io.ArrangedWriter;
import org.openrdf.http.object.io.ProducerStream;
import org.openrdf.http.object.io.ProducerStream.OutputProducer;
import org.openrdf.http.object.io.TurtleStreamWriter;
import org.openrdf.http.object.util.URLUtil;
import org.openrdf.model.Literal;
import org.openrdf.model.Model;
import org.openrdf.model.Statement;
import org.openrdf.model.URI;
import org.openrdf.model.Value;
import org.openrdf.model.impl.TreeModel;
import org.openrdf.model.impl.ValueFactoryImpl;
import org.openrdf.model.vocabulary.RDF;
import org.openrdf.query.GraphQueryResult;
import org.openrdf.query.QueryEvaluationException;
import org.openrdf.rio.RDFFormat;
import org.openrdf.rio.RDFHandler;
import org.openrdf.rio.RDFHandlerException;
import org.openrdf.rio.RDFParseException;
import org.openrdf.rio.RDFParser;
import org.openrdf.rio.RDFParserRegistry;
import org.openrdf.rio.RDFWriter;
import org.openrdf.rio.RDFWriterRegistry;
import org.openrdf.rio.helpers.RDFHandlerWrapper;
import org.openrdf.rio.helpers.StatementCollector;

public abstract class GraphStoreSupport {
	static final RDFParserRegistry parserRegistry = RDFParserRegistry
			.getInstance();
	private static final int MAX_DESCRIPTION_SIZE = 1000;
	private static final Charset UTF8 = Consts.UTF_8;
	private static final HttpVersion HTTP11 = HttpVersion.HTTP_1_1;
	private static final String CALLI = "http://callimachusproject.org/rdf/2009/framework#";
	private static final String PREFIX = "PREFIX sd:<http://www.w3.org/ns/sparql-service-description#>\n";
	static final String READER = CALLI + "reader";
	static final String EDITOR = CALLI + "editor";
	private static final int MAX_EMPTY_SIZE = 512;

	final ValueFactoryImpl vf = ValueFactoryImpl.getInstance();
	final URI rdfSource = vf.createURI("http://www.w3.org/ns/ldp#RDFSource");
	final URI foafPrimaryTopic = vf
			.createURI("http://xmlns.com/foaf/0.1/primaryTopic");
	private final AbsoluteTermFactory tf = AbsoluteTermFactory.newInstance();
	private final RDFWriterRegistry reg = RDFWriterRegistry.getInstance();
	private final Map<String, BoundedDescription> descriptions = new TreeMap<String, BoundedDescription>();

	@Sparql(PREFIX + "ASK { $this sd:supportedLanguage sd:SPARQL11Query }")
	public abstract boolean isQuerySupported();

	@Sparql(PREFIX + "ASK { $this sd:supportedLanguage sd:SPARQL11Update }")
	public abstract boolean isUpdateSupported();

	// SPARQL 1.0

	@Method("GET")
	@Path("\\?(.*&)?query=.*")
	@Header("Cache-Control:no-validate")
	@requires(READER)
	public HttpResponse getQueryResult(@Param("query") String query,
			@Param("default-graph-uri") String[] defaultGraphUri,
			@Param("named-graph-uri") String[] namedGraphUri,
			@Param("max-content-length") Integer maxLength,
			@HeaderParam("Accept") String[] accept) throws IOException,
			OpenRDFException {
		HttpUriResponse res = postQuery(defaultGraphUri, namedGraphUri, accept,
				this.toString(), query.getBytes(UTF8));
		StringBuilder sb = new StringBuilder(this.toString());
		sb.append("#query=").append(PercentCodec.encode(query));
		if (defaultGraphUri != null) {
			for (String uri : defaultGraphUri) {
				sb.append("&default-graph-uri=").append(uri);
			}
		}
		if (namedGraphUri != null) {
			for (String uri : namedGraphUri) {
				sb.append("&named-graph-uri=").append(uri);
			}
		}
		sb.append("&error=Result+Too+Long");
		return limitContentLength(res, maxLength, sb);
	}

	@Method("POST")
	@requires(EDITOR)
	public HttpResponse postPercentEncoded(
			@HeaderParam("Accept") String[] accept,
			@HeaderParam("Content-Location") String base,
			@Type("application/x-www-form-urlencoded") Map<String, String[]> map)
			throws IOException, OpenRDFException {
		if (map.containsKey("query")) {
			return postQuery(map.get("default-graph-uri"),
					map.get("named-graph-uri"), accept, base,
					map.get("query")[0].getBytes(UTF8));
		} else if (map != null && map.containsKey("update")) {
			return postUpdate(
					map.get("using-graph-uri"),
					map.get("using-named-graph-uri"),
					base,
					new ByteArrayInputStream(map.get("update")[0]
							.getBytes(UTF8)));
		} else {
			throw new BadRequest("Missing query string");
		}
	}

	// SPARQL 1.1

	@Method("POST")
	@requires(READER)
	public HttpUriResponse postQuery(
			@Param("default-graph-uri") String[] defaultGraphUri,
			@Param("named-graph-uri") String[] namedGraphUri,
			@HeaderParam("Accept") String[] accept,
			@HeaderParam("Content-Location") String base,
			@Type("application/sparql-query") byte[] query) throws IOException,
			OpenRDFException {
		if (query == null || query.length == 0)
			throw new BadRequest("Missing query");
		if (!isQuerySupported())
			throw new MethodNotAllowed("SPARQL Query is not supported on this service");
		return evaluateQuery(defaultGraphUri, namedGraphUri, accept, base, query);
	}

	@Method("POST")
	@requires(EDITOR)
	public HttpResponse postUpdate(
			@Param("using-graph-uri") final String[] usingGraphUri,
			@Param("using-named-graph-uri") final String[] usingNamedGraphUri,
			@HeaderParam("Content-Location") String base,
			@Type("application/sparql-update") InputStream update)
			throws IOException, OpenRDFException {
		if (!isUpdateSupported())
			throw new MethodNotAllowed("SPARQL Update is not supported on this service");
		return executeUpdate(usingGraphUri, usingNamedGraphUri, base, update);
	}

	// SPARQL Default Graph

	@Method("GET")
	@Path("?default")
	@requires(READER)
	public HttpResponse getDefaultGraph(@HeaderParam("Accept") String[] accept)
			throws IOException, OpenRDFException {
		HttpUriResponse res = evaluateQuery(
				"CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }", null, accept);
		if (res.getStatusLine().getStatusCode() == 200 && isGraphEmpty(res)) {
			res.close();
			EntityUtils.consumeQuietly(res.getEntity());
			throw new NotFound("No triples present in the DEFALUT GRAPH");
		}
		return res;
	}

	@Method("DELETE")
	@Path("?default")
	@requires(EDITOR)
	public HttpResponse deleteDefaultGraph() throws IOException,
			OpenRDFException {
		return executeUpdate("DROP DEFAULT", null);
	}

	@Method("PUT")
	@Path("?default")
	@requires(EDITOR)
	public HttpResponse putDefaultGraph(
			@HeaderParam("Content-Type") String contentType,
			@HeaderParam("Content-Location") String base,
			@Type({ "text/turtle", "application/ld+json", "application/rdf+xml" }) final GraphQueryResult payload)
			throws IOException, OpenRDFException {
		return executeUpdate(asInsertData(payload, null, true), base);
	}

	@Method("POST")
	@Path("?default")
	@requires(EDITOR)
	public HttpResponse postDefaultGraph(
			@HeaderParam("Content-Type") String contentType,
			@HeaderParam("Content-Location") String base,
			@Type({ "text/turtle", "application/ld+json", "application/rdf+xml" }) final GraphQueryResult payload)
			throws IOException, OpenRDFException {
		return executeUpdate(asInsertData(payload, null, false), base);
	}

	// SPARQL Indirect Graph Store

	@Method("GET")
	@Path("?graph=")
	@requires(READER)
	public HttpUriResponse getIndirectGraph(@Param("graph") String graph,
			@HeaderParam("Accept") String[] accept) throws IOException,
			OpenRDFException {
		String iri = resolve(graph);
		HttpUriResponse res = evaluateQuery(
				"CONSTRUCT { ?s ?p ?o } WHERE { GRAPH <" + iri
						+ "> { ?s ?p ?o } }", null, accept);
		if (res.getStatusLine().getStatusCode() == 200 && isGraphEmpty(res)) {
			res.close();
			EntityUtils.consumeQuietly(res.getEntity());
			throw new NotFound("No triples present in GRAPH " + iri);
		}
		return res;
	}

	@Method("DELETE")
	@Path("?graph=")
	@requires(EDITOR)
	public HttpResponse deleteIndirectGraph(@Param("graph") String graph)
			throws IOException, OpenRDFException {
		return executeUpdate("DROP GRAPH <" + resolve(graph) + ">", null);
	}

	@Method("PUT")
	@Path("?graph=")
	@requires(EDITOR)
	public HttpResponse putIndirectGraph(
			@Param("graph") String graph,
			@HeaderParam("Content-Type") String contentType,
			@HeaderParam("Content-Location") String base,
			@Type({ "text/turtle", "application/ld+json", "application/rdf+xml" }) final GraphQueryResult payload)
			throws IOException, OpenRDFException {
		return executeUpdate(asInsertData(payload, resolve(graph), true), base);
	}

	@Method("POST")
	@Path("?graph=")
	@requires(EDITOR)
	public HttpResponse postIndirectGraph(
			@Param("graph") String graph,
			@HeaderParam("Content-Type") String contentType,
			@HeaderParam("Content-Location") String base,
			@Type({ "text/turtle", "application/ld+json", "application/rdf+xml" }) final GraphQueryResult payload)
			throws IOException, OpenRDFException {
		return executeUpdate(asInsertData(payload, resolve(graph), false), base);
	}

	@Method("PATCH")
	@Path("?graph=")
	@requires(EDITOR)
	public HttpResponse patchIndirectGraph(@Param("graph") String graph,
			@HeaderParam("Content-Location") String base,
			@Type("application/sparql-update") InputStream update)
			throws IOException, OpenRDFException {
		return postUpdate(new String[] { resolve(graph) }, null, base, update);
	}

	// SPARQL Direct Graph Store

	@Method("GET")
	@Path("[^?].*")
	@requires(READER)
	public HttpResponse getDirectGraph(@Param("0") String path,
			@HeaderParam("Accept") String[] accept) throws IOException,
			OpenRDFException {
		HttpUriResponse res = null;
		String msg;
		try {
			res = getIndirectGraph(this + path, accept);
			StatusLine status = res.getStatusLine();
			msg = status.getReasonPhrase();
			int code = status.getStatusCode();
			boolean ok = code == 200 || code == 203;
			if (code != 404 && code != 405 && !ok || ok && !isGraphEmpty(res))
				return res;
		} catch (NotFound e) {
			msg = e.getMessage();
		}
		if (res != null) {
			res.close();
			EntityUtils.consumeQuietly(res.getEntity());
		}
		// GRAPH Not Found, redirect to DESCRIBE query
		return redirect(msg, this + "?resource=" + PercentCodec.encode(path));
	}

	@Method("DELETE")
	@Path("[^?].*")
	@requires(EDITOR)
	public HttpResponse deleteDirectGraph(@Param("0") String path)
			throws IOException, OpenRDFException {
		return deleteIndirectGraph(this + path);
	}

	@Method("PUT")
	@Path("[^?].*")
	@requires(EDITOR)
	public HttpResponse putDirectGraph(
			@Param("0") String path,
			@HeaderParam("Content-Type") String contentType,
			@HeaderParam("Content-Location") String base,
			@Type({ "text/turtle", "application/ld+json", "application/rdf+xml" }) final GraphQueryResult payload)
			throws IOException, OpenRDFException {
		return putIndirectGraph(this + path, contentType, base, payload);
	}

	@Method("POST")
	@Path("[^?].*")
	@requires(EDITOR)
	public HttpResponse postDirectGraph(
			@Param("0") String path,
			@HeaderParam("Content-Type") String contentType,
			@HeaderParam("Content-Location") String base,
			@Type({ "text/turtle", "application/ld+json", "application/rdf+xml" }) final GraphQueryResult payload)
			throws IOException, OpenRDFException {
		return postIndirectGraph(this + path, contentType, base, payload);
	}

	@Method("PATCH")
	@Path("[^?].*")
	@requires(EDITOR)
	public HttpResponse patchDirectGraph(@Param("0") String path,
			@HeaderParam("Content-Location") String base,
			@Type("application/sparql-update") InputStream update)
			throws IOException, OpenRDFException {
		return patchIndirectGraph(this + path, base, update);
	}

	// Linked Data Platform

	@Method("HEAD")
	@Path("?resource=")
	@requires(READER)
	@Header("Link:<http://www.w3.org/ns/ldp#Resource>;rel=\"type\"")
	public HttpResponse headIndirectResource(
			@Param("resource") String resource,
			@HeaderParam("Accept") String[] accept) throws IOException,
			OpenRDFException {
		String iri = resolve(resource);
		final String base = this.toString() + "?resource=" + resource;
		final Model model = getBoundedDescription(iri, base)
				.getDescriptionGraph();
		BasicHttpResponse res;
		if (model.isEmpty()) {
			res = new BasicHttpResponse(HTTP11, 404, "Nothing is known about "
					+ iri);
		} else {
			res = new BasicHttpResponse(HTTP11, 200, "OK");
		}
		RDFFormat format = getAcceptableRDFFormat(accept);
		if (preferHtml(accept, format.getDefaultMIMEType())) {
			// external HTML transform will convert response
			res.setHeader("ETag", getETag(model, "text/html"));
		} else {
			res.setHeader("ETag", getETag(model, format.getDefaultMIMEType()));
		}
		return res;
	}

	@Method("GET")
	@Path("?resource=")
	@requires(READER)
	@Header("Link:<http://www.w3.org/ns/ldp#Resource>;rel=\"type\"")
	public HttpResponse getIndirectResource(@Param("resource") String resource,
			@HeaderParam("Accept") String[] accept) throws IOException,
			OpenRDFException {
		String iri = resolve(resource);
		final String base = this.toString() + "?resource=" + resource;
		final Model model = getBoundedDescription(iri, base)
				.getDescriptionGraph();
		BasicHttpResponse res;
		if (model.isEmpty()) {
			throw new NotFound("Nothing is known about " + iri);
		} else {
			res = new BasicHttpResponse(HTTP11, 200, "OK");
		}
		RDFFormat format = getAcceptableRDFFormat(accept);
		res.setHeader("ETag", getETag(model, format.getDefaultMIMEType()));
		res.setEntity(asGraphEntity(model, format, base));
		return res;
	}

	@Method("DELETE")
	@Path("?resource=")
	@requires(EDITOR)
	public HttpResponse deleteIndirectResource(
			@Param("resource") String resource,
			@HeaderParam("If-Match") String ifMatch) throws IOException,
			OpenRDFException {
		String iri = resolve(resource);
		String base = this.toString() + "?resource=" + resource;
		BoundedDescription description = getBoundedDescription(iri, base);
		Model graph = description.getDescriptionGraph();
		RDFFormat format = getAcceptableRDFFormat("text/turtle");
		String eTag = getETag(graph, format.getDefaultMIMEType());
		// ETags for each media start with the same prefix
		String prefix = eTag.substring(0, eTag.indexOf('-') + 1);
		if (ifMatch != null && !ifMatch.equals("*") && !ifMatch.startsWith(prefix)) {
			BasicHttpResponse res = new BasicHttpResponse(HTTP11, 412,
					"Precondition Failed");
			res.setHeader("ETag", eTag);
			res.setEntity(asGraphEntity(graph, format, base));
			return res;
		}
		return executeDescriptionUpdate(iri, base, description.getDeleteUpdate());
	}

	@Method("PUT")
	@Path("?resource=")
	@requires(EDITOR)
	public HttpResponse putIndirectResource(
			@Param("resource") String resource,
			@HeaderParam("If-Match") String ifMatch,
			@HeaderParam("Content-Type") String contentType,
			@Type({ "text/turtle", "application/ld+json", "application/rdf+xml" }) final Model replacementData)
			throws IOException, OpenRDFException {
		String iri = resolve(resource);
		String base = this.toString() + "?resource=" + resource;
		BoundedDescription description = getBoundedDescription(iri, base);
		Model deleteData = description.getDescriptionGraph();
		RDFFormat format = getAcceptableRDFFormat(contentType);
		String eTag = getETag(deleteData, format.getDefaultMIMEType());
		if (ifMatch == null) {
			throw new PreconditionRequired(
					"If-Match request header is required");
		} else if (!ifMatch.equals("*") && !ifMatch.equals(eTag)) {
			BasicHttpResponse res = new BasicHttpResponse(HTTP11, 412,
					"Precondition Failed");
			res.setHeader("ETag", eTag);
			res.setEntity(asGraphEntity(deleteData, format, base));
			return res;
		}
		description.verifyDescriptionGraph(replacementData, deleteData);
		SparqlUpdateFactory factory = new SparqlUpdateFactory(base);
		String sparql = factory.replacement(deleteData, replacementData);
		return executeDescriptionUpdate(iri, base, sparql);
	}

	protected abstract HttpUriResponse evaluateQuery(String[] defaultGraphUri,
			String[] namedGraphUri, String[] accept, String base, byte[] query)
			throws IOException, OpenRDFException;

	protected abstract HttpResponse executeUpdate(String[] usingGraphUri,
			String[] usingNamedGraphUri, String base, InputStream update)
			throws IOException, OpenRDFException;

	String resolve(String iri) {
		if (iri == null || iri.contains(">"))
			throw new BadRequest("Invalid IRI: " + iri);
		return URLUtil.resolve(iri, this.toString());
	}

	private BoundedDescription getBoundedDescription(final String iri,
			final String doc) {
		synchronized (descriptions) {
			if (descriptions.containsKey(iri))
				return descriptions.get(iri);
			BoundedDescription value = new BoundedDescription(iri, MAX_DESCRIPTION_SIZE) {

				@Override
				protected void evaluate(String construct, RDFHandler h)
						throws IOException, OpenRDFException {
					evaluateRemotely(construct, iri, doc, h);
				}
			};
			descriptions.put(iri, value);
			return value;
		}
	}

	private HttpResponse executeDescriptionUpdate(String iri, String base,
			String sparql) throws IOException, OpenRDFException {
		try {
			return executeUpdate(sparql, base);
		} finally {
			synchronized (descriptions) {
				descriptions.remove(iri);
			}
		}
	}

	void evaluateRemotely(String construct, final String iri, final String doc,
			RDFHandler handler) throws IOException, OpenRDFException {
		HttpUriResponse res = evaluateQuery(construct, doc, "text/turtle");
		InputStream in = res.getEntity().getContent();
		try {
			final RDFHandlerWrapper h = new RDFHandlerWrapper(handler) {
				@Override
				public void handleStatement(Statement st)
						throws RDFHandlerException {
					URI s = vf.createURI(doc);
					URI o = vf.createURI(iri);
					super.handleStatement(vf.createStatement(s, RDF.TYPE,
							rdfSource));
					super.handleStatement(vf.createStatement(s,
							foafPrimaryTopic, o));
					super.handleStatement(st);
				}
			};
			parseRDF(res.getEntity().getContentType(), in, res.getSystemId(), h);
		} finally {
			in.close();
			res.close();
		}
	}

	protected String[] split(String[] accept) {
		boolean comma = false;
		for (String a : accept) {
			if (a.indexOf(',') >= 0) {
				comma = true;
				break;
			}
		}
		if (!comma)
			return accept;
		if (accept.length == 1)
			return accept[0].split("\\s*,\\s*");
		List<String> list = new ArrayList<String>();
		for (String a : accept) {
			list.addAll(Arrays.asList(a.split("\\s*,\\s*")));
		}
		return list.toArray(new String[list.size()]);
	}

	protected RDFFormat getAcceptableRDFFormat(String... accept) {
		if (accept == null)
			return RDFFormat.TURTLE;
		String type = new FluidType(Model.class, "text/turtle",
				"application/ld+json", "application/rdf+xml").as(
				split(accept)).preferred();
		return reg.getFileFormatForMIMEType(type, RDFFormat.TURTLE);
	}

	private boolean preferHtml(String[] accept, String over) {
		if (accept == null)
			return true;
		String type = new FluidType(Model.class, "text/html", over).as(
				split(accept)).preferred();
		return type.startsWith("text/html");
	}

	InputStreamEntity asGraphEntity(final Model model, final RDFFormat format,
			final String base) throws IOException {
		return new InputStreamEntity(new ProducerStream(new OutputProducer() {
			public void produce(OutputStream out) throws IOException {
				try {
					write(model, format, out, base);
				} catch (RDFHandlerException e) {
					throw new IOException(e);
				} catch (URISyntaxException e) {
					throw new IOException(e);
				}
			}
		}), ContentType.create(format.getDefaultMIMEType()));
	}

	void write(Model model, RDFFormat format, OutputStream out, String base)
			throws URISyntaxException, RDFHandlerException {
		RDFWriter writer = getRDFWriter(format, out, base);
		writer.startRDF();
		for (org.openrdf.model.Namespace ns : model.getNamespaces()) {
			writer.handleNamespace(ns.getPrefix(), ns.getName());
		}
		for (Statement st : model) {
			writer.handleStatement(st);
		}
		writer.endRDF();
	}

	private RDFWriter getRDFWriter(final RDFFormat format, OutputStream out,
			String systemId) throws URISyntaxException {
		if (RDFFormat.TURTLE.equals(format)) {
			return new ArrangedWriter(new TurtleStreamWriter(out, systemId));
		} else {
			return new ArrangedWriter(reg.get(format).getWriter(out));
		}
	}

	private Model parseRDF(org.apache.http.Header contentType, InputStream in,
			String base) throws IOException, RDFParseException,
			RDFHandlerException {
		final Model model = new TreeModel();
		parseRDF(contentType, in, base, new StatementCollector(model) {
			@Override
			public void handleNamespace(String prefix, String uri)
					throws RDFHandlerException {
				model.setNamespace(prefix, uri);
			}
		});
		return model;
	}

	private void parseRDF(org.apache.http.Header contentType, InputStream in,
			String base, RDFHandler handler) throws IOException,
			RDFParseException, RDFHandlerException {
		RDFFormat format = RDFFormat.TURTLE;
		if (contentType != null) {
			String type = contentType.getValue();
			format = parserRegistry.getFileFormatForMIMEType(type, format);
		}
		RDFParser parser = parserRegistry.get(format).getParser();
		parser.setRDFHandler(handler);
		parser.parse(in, base);
	}

	private String getETag(final Model model, String media) {
		return "W/\"" + Integer.toHexString(model.hashCode()) + '-'
				+ Integer.toHexString(media.hashCode()) + "\"";
	}

	private HttpUriResponse evaluateQuery(String query, String base, String... accept)
			throws IOException, OpenRDFException {
		return evaluateQuery(null, null, accept, base, query.getBytes(UTF8));
	}

	private HttpResponse executeUpdate(String update, String base)
			throws IOException, OpenRDFException {
		return executeUpdate(new ByteArrayInputStream(update.getBytes(UTF8)), base);
	}

	private HttpResponse executeUpdate(InputStream update, String base)
			throws IOException, OpenRDFException {
		return executeUpdate(null, null, base, update);
	}

	private boolean isGraphEmpty(HttpUriResponse res) throws IOException,
			OpenRDFException {
		HttpUriEntity entity = res.getEntity();
		long contentLength = entity.getContentLength();
		if (contentLength > 512)
			return false;
		final BufferedInputStream bin = bufferAll(res, MAX_EMPTY_SIZE);
		if (bin == null)
			return false;
		String b = res.getSystemId();
		try {
			return parseRDF(entity.getContentType(), bin, b).isEmpty();
		} finally {
			bin.reset();
		}
	}

	private BufferedInputStream bufferAll(HttpResponse res, int size)
			throws IOException {
		HttpEntity entity = res.getEntity();
		long contentLength = entity.getContentLength();
		if (contentLength > size)
			return null;
		InputStream in = entity.getContent();
		final BufferedInputStream bin = new BufferedInputStream(in, size) {
			public void close() throws IOException {
				// don't allow xerces to close stream
			}
		};
		override(res, bin);
		bin.mark(size);
		long skipped = bin.skip(size);
		bin.reset();
		if (skipped < size) {
			bin.mark(size);
			return bin;
		}
		return null; // too big
	}

	private void override(HttpResponse res, final InputStream bin) {
		HttpEntity entity = res.getEntity();
		res.setEntity(new StreamingHttpEntity(entity) {
			@Override
			protected InputStream getDelegateContent() throws IOException {
				return bin;
			}
		});
	}

	private HttpResponse limitContentLength(HttpUriResponse res,
			Integer maxLength, CharSequence redirect) throws IOException {
		if (maxLength == null)
			return res;
		if (maxLength <= 0 || maxLength > 1024 * 1024
				&& maxLength > Runtime.getRuntime().freeMemory()) {
			res.close();
			EntityUtils.consumeQuietly(res.getEntity());
			throw new BadRequest("Invalid max-content-length: " + maxLength);
		}
		InputStream in = res.getEntity().getContent();
		BufferedInputStream bin = new BufferedInputStream(in, maxLength);
		long length = bin.skip(maxLength + 1);
		if (length >= maxLength) {
			res.close();
			EntityUtils.consumeQuietly(res.getEntity());
			return redirect("Result is too long", redirect.toString());
		} else {
			bin.reset();
			InputStreamEntity entity = new InputStreamEntity(bin, length);
			entity.setContentType(res.getEntity().getContentType());
			res.setEntity(entity);
			return res;
		}
	}

	private HttpResponse redirect(String phrase, String target) {
		HttpResponse redirect = new BasicHttpResponse(HTTP11, 303, phrase);
		redirect.setHeader("Location", target);
		ContentType type = ContentType.create("text/uri-list", UTF8);
		redirect.setEntity(new StringEntity(target, type));
		return redirect;
	}

	private InputStream asInsertData(final GraphQueryResult payload,
			final String graph, final boolean drop) throws IOException {
		return new ProducerStream(new OutputProducer() {
			public void produce(OutputStream out) throws IOException {
				try {
					try {
						SPARQLWriter writer = new SPARQLWriter(out);
						try {
							write(payload, graph, drop, writer);
						} finally {
							writer.close();
						}
					} finally {
						payload.close();
					}
				} catch (QueryEvaluationException e) {
					throw new IOException(e);
				}
			}
		});
	}

	void write(GraphQueryResult payload, String graph, boolean drop,
			SPARQLWriter writer) throws QueryEvaluationException, IOException {
		for (Map.Entry<String, String> e : payload.getNamespaces().entrySet()) {
			writer.write(new Namespace(e.getKey(), e.getValue()));
		}
		if (drop && graph == null) {
			writer.write(new Drop(true, "DEFAULT", null));
		} else if (drop) {
			writer.write(new Drop(true, tf.iri(graph), null));
		}
		writer.write(new InsertData(true, null));
		if (graph != null) {
			writer.write(new Graph(true, tf.iri(graph), null));
		}
		while (payload.hasNext()) {
			Statement st = payload.next();
			writer.write(asTriple(st));
		}
		if (graph != null) {
			writer.write(new Graph(false, tf.iri(graph), null));
		}
		writer.write(new InsertData(false, null));
	}

	private Triple asTriple(Statement st) {
		return new Triple((Node) asTerm(st.getSubject()),
				(IRI) asTerm(st.getPredicate()), asTerm(st.getObject()));
	}

	private Term asTerm(Value obj) {
		if (obj instanceof Literal) {
			Literal lit = (Literal) obj;
			if (lit.getDatatype() != null) {
				return tf.literal(obj.stringValue(),
						tf.iri(lit.getDatatype().stringValue()));
			} else if (lit.getLanguage() != null) {
				return tf.literal(obj.stringValue(), lit.getLanguage());
			} else {
				return tf.literal(obj.stringValue());
			}
		} else if (obj instanceof URI) {
			return tf.iri(obj.stringValue());
		} else {
			return tf.node(obj.stringValue());
		}
	}
}
