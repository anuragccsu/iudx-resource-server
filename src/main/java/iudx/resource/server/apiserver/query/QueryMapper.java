package iudx.resource.server.apiserver.query;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import iudx.resource.server.apiserver.util.Constants;
import java.util.Arrays;
import java.util.List;

/**
 * QueryMapper class to convert NGSILD query into json object for the purpose of information
 * exchange among different verticals.
 * TODO Need to add documentation.
 */
public class QueryMapper {

  private static final Logger LOGGER = LogManager.getLogger(QueryMapper.class);
  private boolean isTemporal = false;
  private boolean isGeoSearch = false;
  private boolean isResponseFilter = false;
  private boolean isAttributeSearch = false;

  /**
   * This method is used to create a json object from NGSILDQueryParams.
   * 
   * @param params A map of query parameters passed.
   * @param isTemporal flag indicating whether temporal or not.
   * @return JsonObject result.
   */
  public JsonObject toJson(NGSILDQueryParams params, boolean isTemporal) {
    LOGGER.debug("Info QuerryMapper#toJson() started");
    LOGGER.debug("Info : params" + params);
    this.isTemporal = isTemporal;
    JsonObject json = new JsonObject();

    if (params.getId() != null) {
      JsonArray jsonArray = new JsonArray();
      params.getId().forEach(s -> jsonArray.add(s.toString()));
      json.put(Constants.JSON_ID, jsonArray);
      LOGGER.debug("Info : json " + json);
    }
    if (params.getAttrs() != null) {
      isResponseFilter = true;
      JsonArray jsonArray = new JsonArray();
      params.getAttrs().forEach(attribute -> jsonArray.add(attribute));
      json.put(Constants.JSON_ATTRIBUTE_FILTER, jsonArray);
      LOGGER.debug("Info : json " + json);
    }
    if (params.getGeoRel() != null
        && (params.getCoordinates() != null || params.getGeometry() != null)) {
      isGeoSearch = true;
      if (params.getGeometry().equalsIgnoreCase(Constants.GEOM_POINT)
          && params.getGeoRel().getRelation().equals(Constants.JSON_NEAR)
          && params.getGeoRel().getMaxDistance() != null) {
        String[] coords = params.getCoordinates().replaceAll("\\[|\\]", "").split(",");
        json.put(Constants.JSON_LAT, Double.parseDouble(coords[0]));
        json.put(Constants.JSON_LON, Double.parseDouble(coords[1]));
        json.put(Constants.JSON_RADIUS, params.getGeoRel().getMaxDistance());
      } else {
        json.put(Constants.JSON_GEOMETRY, params.getGeometry());
        json.put(Constants.JSON_COORDINATES, params.getCoordinates());
        json.put(Constants.JSON_GEOREL,
            getOrDefault(params.getGeoRel().getRelation(), Constants.JSON_WITHIN));
        if (params.getGeoRel().getMaxDistance() != null) {
          json.put(Constants.JSON_MAXDISTANCE, params.getGeoRel().getMaxDistance());
        } else if (params.getGeoRel().getMinDistance() != null) {
          json.put(Constants.JSON_MINDISTANCE, params.getGeoRel().getMinDistance());
        }
      }
      LOGGER.debug("Info : json " + json);
    }
    if (isTemporal && params.getTemporalRelation().getTemprel() != null
        && params.getTemporalRelation().getTime() != null) {
      isTemporal = true;
      if (params.getTemporalRelation().getTemprel().equalsIgnoreCase(Constants.JSON_DURING)) {
        json.put(Constants.JSON_TIME, params.getTemporalRelation().getTime().toString());
        json.put(Constants.JSON_ENDTIME, params.getTemporalRelation().getEndTime().toString());
        json.put(Constants.JSON_TIMEREL, params.getTemporalRelation().getTemprel());
      } else {
        json.put(Constants.JSON_TIME, params.getTemporalRelation().getTime().toString());
        json.put(Constants.JSON_TIMEREL, params.getTemporalRelation().getTemprel());
      }
      LOGGER.debug("Info : json " + json);
    }
    if (params.getQ() != null) {
      isAttributeSearch = true;
      JsonArray query = new JsonArray();
      String[] qterms = params.getQ().split(";");
      for (String term : qterms) {
        query.add(getQueryTerms(term));
      }
      json.put(Constants.JSON_ATTR_QUERY, query);
      LOGGER.debug("Info : json " + json);
    }
    if (params.getGeoProperty() != null) {
      json.put(Constants.JSON_GEOPROPERTY, params.getGeoProperty());
      LOGGER.debug("Info : json " + json);
    }
    if (params.getOptions() != null) {
      json.put(Constants.IUDXQUERY_OPTIONS, params.getOptions());
      LOGGER.debug("Info : json " + json);
    }

    json.put(Constants.JSON_SEARCH_TYPE, getSearchType());
    LOGGER.debug("Info : json " + json);
    return json;
  }

  private <T> T getOrDefault(T value, T def) {
    return (value == null) ? def : value;
  }

  private String getSearchType() {
    StringBuilder searchType = new StringBuilder();
    if (isTemporal) {
      searchType.append(Constants.JSON_TEMPORAL_SEARCH);
    } else {
      searchType.append(Constants.JSON_LATEST_SEARCH);
    }
    if (isGeoSearch) {
      searchType.append(Constants.JSON_GEO_SEARCH);
    }
    if (isResponseFilter) {
      searchType.append(Constants.JSON_RESPONSE_FILTER_SEARCH);
    }
    if (isAttributeSearch) {
      searchType.append(Constants.JSON_ATTRIBUTE_SEARCH);
    }
    return searchType.substring(0, searchType.length() - 1).toString();
  }

  JsonObject getQueryTerms(String queryTerms) {
    JsonObject json = new JsonObject();
    int length = queryTerms.length();
    List<Character> allowedSpecialCharacter = Arrays.asList('>', '=', '<', '!');
    int startIndex = 0;
    boolean specialCharFound = false;
    for (int i = 0; i < length; i++) {
      Character c = queryTerms.charAt(i);
      if (!(Character.isLetter(c) || Character.isDigit(c)) && !specialCharFound) {
        if (allowedSpecialCharacter.contains(c)) {
          json.put(Constants.JSON_ATTRIBUTE, queryTerms.substring(startIndex, i));
          startIndex = i;
          specialCharFound = true;
        } else {
          LOGGER.info("Ignore " + c.toString());
        }
      } else {
        if (specialCharFound && (Character.isLetter(c) || Character.isDigit(c))) {
          json.put(Constants.JSON_OPERATOR, queryTerms.substring(startIndex, i));
          json.put(Constants.JSON_VALUE, queryTerms.substring(i));
          break;
        }
      }

    }
    return json;
  }

}
