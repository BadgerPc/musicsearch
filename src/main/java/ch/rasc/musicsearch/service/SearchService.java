package ch.rasc.musicsearch.service;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParserBase;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import ch.ralscha.extdirectspring.annotation.ExtDirectMethod;
import ch.ralscha.extdirectspring.annotation.ExtDirectMethodType;
import ch.ralscha.extdirectspring.bean.ExtDirectStoreReadRequest;
import ch.ralscha.extdirectspring.filter.StringFilter;
import ch.rasc.musicsearch.model.Info;
import ch.rasc.musicsearch.model.Song;

import com.google.common.collect.Lists;

@Service
public class SearchService {

	private static final Log logger = LogFactory.getLog(SearchService.class);

	private final static String[] FIELDS = { "fileName", "directory", "title", "artist", "album", "comment", "year",
			"composer" };

	@Autowired
	private Environment environement;

	private Directory indexDirectory;

	private IndexReader indexReader;

	private IndexSearcher indexSearcher;

	@PostConstruct
	public void init() {
		Path ixDir = Paths.get(environement.getProperty("indexDir"));
		try {
			indexDirectory = FSDirectory.open(ixDir.toFile());
			indexReader = DirectoryReader.open(indexDirectory);
			indexSearcher = new IndexSearcher(indexReader);
		} catch (IOException e) {
			logger.error("init searcher service", e);
		}
	}

	@PreDestroy
	public void destroy() {
		if (indexReader != null) {
			try {
				indexReader.close();
			} catch (IOException e) {
				// ignore exception
			}
		}
		if (indexDirectory != null) {
			try {
				indexDirectory.close();
			} catch (IOException e) {
				// ignore exception
			}
		}
	}

	@ExtDirectMethod
	public Info getInfo() {

		Info info = new Info();
		try {
			Path infoFile = Paths.get(environement.getProperty("indexDir"), "info.properties");
			Properties properties;
			try (FileReader fr = new FileReader(infoFile.toFile())) {
				properties = new Properties();
				properties.load(fr);
			}

			String totalDurationString = (String) properties.get("totalDuration");
			String noOfSongsString = (String) properties.get("noOfSongs");

			if (StringUtils.isNotBlank(totalDurationString)) {
				info.setTotalDuration(Integer.valueOf(totalDurationString));
			}
			if (StringUtils.isNotBlank(noOfSongsString)) {
				info.setNoOfSongs(Integer.valueOf(noOfSongsString));
			}

		} catch (FileNotFoundException e) {
			logger.error("getInfo", e);
		} catch (IOException e) {
			logger.error("getInfo", e);
		}

		return info;
	}

	@ExtDirectMethod(value = ExtDirectMethodType.STORE_READ)
	public List<Song> search(ExtDirectStoreReadRequest storeRequest) {

		String filterValue = null;
		if (!storeRequest.getFilters().isEmpty()) {
			StringFilter filter = (StringFilter) storeRequest.getFilters().iterator().next();
			filterValue = filter.getValue();
		}

		logger.info("SEARCH FOR: " + filterValue);

		List<Song> resultList = Lists.newArrayList();

		try (Analyzer analyzer = new StandardAnalyzer(Version.LUCENE_40)) {

			MultiFieldQueryParser parser = new MultiFieldQueryParser(Version.LUCENE_40, FIELDS, analyzer);
			parser.setDefaultOperator(QueryParserBase.AND_OPERATOR);
			parser.setAllowLeadingWildcard(true);

			Query query;
			try {
				query = parser.parse(filterValue);
			} catch (ParseException e) {
				try {
					query = parser.parse(QueryParserBase.escape(filterValue));
				} catch (ParseException e1) {
					logger.error("lucene query parse", e1);
					return Collections.emptyList();
				}
			}

			TopDocs results = indexSearcher.search(query, 10000);
			logger.info("FOUND:      " + results.totalHits);
			for (ScoreDoc scoreDoc : results.scoreDocs) {
				Document doc = indexSearcher.doc(scoreDoc.doc);

				Song song = new Song();

				song.setFileName(doc.get("directory") + "/" + doc.get("fileName"));
				song.setTitle(doc.get("title"));
				song.setArtist(doc.get("artist"));
				song.setAlbum(doc.get("album"));
				song.setYear(doc.get("year"));

				if (StringUtils.isBlank(song.getArtist()) && StringUtils.isBlank(song.getAlbum())
						&& StringUtils.isBlank(song.getTitle())) {
					song.setTitle(song.getFileName());
				}

				IndexableField field = doc.getField("duration");
				if (field != null) {
					song.setDurationInSeconds(field.numericValue().intValue());
				} else {
					song.setDurationInSeconds(null);
				}

				field = doc.getField("bitrate");
				if (field != null) {
					song.setBitrate(field.numericValue().longValue());
				} else {
					song.setBitrate(null);
				}

				resultList.add(song);

			}

		} catch (IOException e) {
			e.printStackTrace();
		}

		return resultList;

	}
}
