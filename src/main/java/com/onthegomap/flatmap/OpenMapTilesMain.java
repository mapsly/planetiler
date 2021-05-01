package com.onthegomap.flatmap;

import com.onthegomap.flatmap.collections.FeatureGroup;
import com.onthegomap.flatmap.collections.FeatureSort;
import com.onthegomap.flatmap.collections.LongLongMap;
import com.onthegomap.flatmap.profiles.OpenMapTilesProfile;
import com.onthegomap.flatmap.reader.NaturalEarthReader;
import com.onthegomap.flatmap.reader.OpenStreetMapReader;
import com.onthegomap.flatmap.reader.ShapefileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OpenMapTilesMain {

  private static final Logger LOGGER = LoggerFactory.getLogger(OpenMapTilesMain.class);

  public static void main(String[] args) throws IOException {
    Arguments arguments = new Arguments(args);
    var stats = arguments.getStats();
    stats.startTimer("import");
    LOGGER.info("Arguments:");
    Path sourcesDir = Path.of("data", "sources");
    OsmInputFile osmInputFile = new OsmInputFile(
      arguments.inputFile("input", "OSM input file", sourcesDir.resolve("north-america_us_massachusetts.pbf")));
    Path centerlines = arguments
      .inputFile("centerline", "lake centerlines input", sourcesDir.resolve("lake_centerline.shp.zip"));
    Path naturalEarth = arguments
      .inputFile("natural_earth", "natural earth input", sourcesDir.resolve("natural_earth_vector.sqlite.zip"));
    Path waterPolygons = arguments
      .inputFile("water_polygons", "water polygons input", sourcesDir.resolve("water-polygons-split-3857.zip"));
    Path tmpDir = arguments.file("tmpdir", "temp directory", Path.of("data", "tmp"));
    boolean fetchWikidata = arguments.get("fetch_wikidata", "fetch wikidata translations", false);
    boolean useWikidata = arguments.get("use_wikidata", "use wikidata translations", true);
    Path wikidataNamesFile = arguments.file("wikidata_cache", "wikidata cache file",
      Path.of("data", "sources", "wikidata_names.json"));
    Path output = arguments.file("output", "mbtiles output file", Path.of("massachusetts.mbtiles"));
    List<String> languages = arguments.get("name_languages", "languages to use",
      "en,ru,ar,zh,ja,ko,fr,de,fi,pl,es,be,br,he".split(","));
    CommonParams config = CommonParams.from(arguments, osmInputFile);

    LOGGER.info("Building OpenMapTiles profile into " + output + " in these phases:");
    if (fetchWikidata) {
      LOGGER.info("  [wikidata] Fetch OpenStreetMap element name translations from wikidata");
    }
    LOGGER.info("  [lake_centerlines] Extract lake centerlines");
    LOGGER.info("  [water_polygons] Process ocean polygons");
    LOGGER.info("  [natural_earth] Process natural earth features");
    LOGGER.info("  [osm_pass1] Pre-process OpenStreetMap input (store node locations then relation members)");
    LOGGER.info("  [osm_pass2] Process OpenStreetMap nodes, ways, then relations");
    LOGGER.info("  [sort] Sort rendered features by tile ID");
    LOGGER.info("  [mbtiles] Encode each tile and write to " + output);

    var translations = Translations.defaultProvider(languages);
    var profile = new OpenMapTilesProfile();

    FileUtils.forceMkdir(tmpDir.toFile());
    Path nodeDb = tmpDir.resolve("node.db");
    LongLongMap nodeLocations = new LongLongMap.MapdbSortedTable(nodeDb);
    FeatureSort featureDb = FeatureSort.newExternalMergeSort(tmpDir.resolve("feature.db"), config.threads(), stats);
    FeatureGroup featureMap = new FeatureGroup(featureDb, profile);
    FeatureRenderer renderer = new FeatureRenderer(config);

    if (fetchWikidata) {
      stats.time("wikidata", () -> Wikidata.fetch(osmInputFile, wikidataNamesFile, config, profile, stats));
    }
    if (useWikidata) {
      translations.addTranslationProvider(Wikidata.load(wikidataNamesFile));
    }

    stats.time("lake_centerlines", () ->
      ShapefileReader
        .process("EPSG:3857", "lake_centerlines", centerlines, renderer, featureMap, config, profile, stats));
    stats.time("water_polygons", () ->
      ShapefileReader.process("water_polygons", waterPolygons, renderer, featureMap, config, profile, stats));
    stats.time("natural_earth", () ->
      new NaturalEarthReader(naturalEarth, tmpDir.resolve("natearth.sqlite"), profile, stats)
        .process("natural_earth", renderer, featureMap, config)
    );

    AtomicLong featureCount = new AtomicLong(0);
    try (var osmReader = new OpenStreetMapReader(osmInputFile, nodeLocations, profile, stats)) {
      stats.time("osm_pass1", () -> osmReader.pass1(config));
      stats.time("osm_pass2", () -> featureCount.set(osmReader.pass2(renderer, featureMap, config)));
    }

    LOGGER.info("Deleting node.db to make room for mbtiles");
    profile.release();
    Files.delete(nodeDb);

    stats.time("sort", featureDb::sort);
    stats
      .time("mbtiles", () -> MbtilesWriter.writeOutput(featureCount.get(), featureMap, output, profile, config, stats));

    stats.stopTimer("import");

    LOGGER.info("FINISHED!");

    stats.printSummary();
  }
}
