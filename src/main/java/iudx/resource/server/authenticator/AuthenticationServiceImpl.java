package iudx.resource.server.authenticator;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.apache.http.HttpStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import io.vertx.core.AsyncResult;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.client.predicate.ResponsePredicate;
import iudx.resource.server.databroker.util.Util;

/**
 * The Authentication Service Implementation.
 * <h1>Authentication Service Implementation</h1>
 * <p>
 * The Authentication Service implementation in the IUDX Resource Server implements the definitions
 * of the {@link iudx.resource.server.authenticator.AuthenticationService}.
 * </p>
 *
 * @version 1.0
 * @since 2020-05-31
 */

public class AuthenticationServiceImpl implements AuthenticationService {

  private static final Logger LOGGER = LogManager.getLogger(AuthenticationServiceImpl.class);
  private static final ConcurrentHashMap<String, JsonObject> tipCache = new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<String, String> catCache = new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<String, String> catrIDCache = new ConcurrentHashMap<>();
  private final WebClient webClient;
  private final Vertx vertxObj;
  private JsonObject config;

  /**
   * This is a constructor which is used by the DataBroker Verticle to instantiate a RabbitMQ
   * client.
   * 
   * @param vertx which is a vertx instance
   * @param client which is a Vertx Web client
   */

  public AuthenticationServiceImpl(Vertx vertx, WebClient client, JsonObject config) {
    webClient = client;
    vertxObj = vertx;
    this.config = config;

    long cacheCleanupTime = 1000 * 60 * Constants.TIP_CACHE_TIMEOUT_AMOUNT;
    vertx.setPeriodic(cacheCleanupTime, timerID -> tipCache.values().removeIf(entry -> {
      Instant tokenExpiry = Instant.parse(entry.getString("expiry"));
      Instant cacheExpiry = Instant.parse(entry.getString("cache-expiry"));
      Instant now = Instant.now(Clock.systemUTC());
      return (now.isAfter(tokenExpiry) || now.isAfter(cacheExpiry));
    }));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public AuthenticationService tokenInterospect(JsonObject request, JsonObject authenticationInfo,
      Handler<AsyncResult<JsonObject>> handler) {

    System.out.println(authenticationInfo);
    String token = authenticationInfo.getString("token");
    String requestEndpoint = authenticationInfo.getString("apiEndpoint");

    LOGGER.debug("Info: requested endpoint :" + requestEndpoint);

    if (config.getString(Constants.SERVER_MODE).equalsIgnoreCase("testing")) {
      if (token.equals(Constants.PUBLIC_TOKEN)
          && Constants.OPEN_ENDPOINTS.contains(requestEndpoint)) {
        JsonObject result = new JsonObject();
        result.put("status", "success");
        handler.handle(Future.succeededFuture(result));
        return this;
      } else if (token.equals(Constants.PUBLIC_TOKEN)
          && !Constants.OPEN_ENDPOINTS.contains(requestEndpoint)) {
        JsonObject result = new JsonObject();
        result.put(Constants.JSON_CONSUMER, Constants.JSON_TEST_CONSUMER);
        result.put(Constants.JSON_PROVIDER, Constants.JSON_TEST_PROVIDER_SHA);
        handler.handle(Future.succeededFuture(result));
        return this;
      } else if (!token.equals(Constants.PUBLIC_TOKEN)) {
        // Perform TIP with Auth Server
        Future<JsonObject> tipResponseFut = retrieveTipResponse(token);
        // Check if resource is Open or Secure with Catalogue Server
        Future<HashMap<String, Boolean>> catResponseFut =
            isOpenResource(request.getJsonArray("ids"), requestEndpoint);
        CompositeFuture.all(tipResponseFut, catResponseFut).onFailure(failedHandler -> {
          LOGGER.debug("Info: TIP / Cat Failed");
          JsonObject result = new JsonObject();
          result.put("status", "error");
          result.put("message", failedHandler.getMessage());
          handler.handle(Future.failedFuture(result.toString()));
        }).onSuccess(successHandler -> {
          JsonObject tipResponse = successHandler.resultAt(0);
          HashMap<String, Boolean> catResponse = successHandler.resultAt(1);
          LOGGER.debug("Info: TIP Response is : " + tipResponse);
          LOGGER.debug("Info: CAT Response is : " + Collections.singletonList(catResponse));
          
          Future<JsonObject> validateAPI = validateAccess(tipResponse, catResponse, authenticationInfo, request);
          
          validateAPI.onComplete(validateAPIResponseHandler -> {
            if(validateAPIResponseHandler.succeeded()) {
              LOGGER.debug("Info: Success :: TIP Response is : " + tipResponse);
              JsonObject response = validateAPIResponseHandler.result();
              handler.handle(Future.succeededFuture(response));
            } else if (validateAPIResponseHandler.failed()){
              LOGGER.debug("Info: Failure :: TIP Response is : " + tipResponse);
              String response = validateAPIResponseHandler.cause().getMessage();
              handler.handle(Future.failedFuture(response));
            }
          });
        });
        return this;
      }

    } else {
      if (token.equals(Constants.PUBLIC_TOKEN)
          && !Constants.OPEN_ENDPOINTS.contains(requestEndpoint)) {
        JsonObject result = new JsonObject();
        result.put("status", "error");
        result.put("message", "Public token cannot access requested endpoint");
        handler.handle(Future.failedFuture(result.toString()));
        return this;
      } else {
        // Based on API perform TIP. 
        // For management and subscription no need to look-up at catalogue
        Future<JsonObject> tipResponseFut = retrieveTipResponse(token);
        Future<HashMap<String, Boolean>> catResponseFut =
            isOpenResource(request.getJsonArray("ids"), requestEndpoint);
        // Based on catalogue item accessPolicy, decide the TIP
        CompositeFuture.all(tipResponseFut, catResponseFut).onFailure(throwable -> {
          LOGGER.debug("Info: TIP / Cat Failed");
          JsonObject result = new JsonObject();
          result.put("status", "error");
          result.put("message", throwable.getMessage());
          handler.handle(Future.failedFuture(result.toString()));
        }).onSuccess(compositeFuture -> {
          JsonObject tipResponse = compositeFuture.resultAt(0);
          HashMap<String, Boolean> catResponse = compositeFuture.resultAt(1);
          LOGGER.debug("Info: TIP Response is : " + tipResponse);
          LOGGER.debug("Info: CAT Response is : " + Collections.singletonList(catResponse));
          
          Future<JsonObject> validateAPI = validateAccess(tipResponse, catResponse, authenticationInfo, request);
          validateAPI.onComplete(validateAPIResponseHandler -> {
            if(validateAPIResponseHandler.succeeded()) {
              LOGGER.debug("Info: Success :: TIP Response is : " + tipResponse);
              JsonObject response = validateAPIResponseHandler.result();
              handler.handle(Future.succeededFuture(response));
            } else if (validateAPIResponseHandler.failed()){
              LOGGER.debug("Info: Failure :: TIP Response is : " + tipResponse);
              String response = validateAPIResponseHandler.cause().getMessage();
              handler.handle(Future.failedFuture(response));
            }
          });
        });
        return this;
      }
    }
    return this;
  }

  private boolean isValidEndpoint(String requestEndpoint, JsonArray apis) {
    for (Object es : apis) {
      String endpoint = (String) es;
      if (endpoint.equals("/*") || endpoint.equals(requestEndpoint)) {
        return true;
      }
    }
    return false;
  }

  private JsonObject retrieveTipRequest(String requestID, JsonObject tipResponse) {
    for (Object r : tipResponse.getJsonArray("request")) {
      JsonObject tipRequest = (JsonObject) r;
      String responseID = tipRequest.getString("id");
      if (requestID.equals(responseID)) {
        return tipRequest;
      }
      String escapedResponseID =
          responseID.replace("/", "\\/").replace(".", "\\.").replace("*", ".*");
      Pattern pattern = Pattern.compile(escapedResponseID);
      if (pattern.matcher(requestID).matches()) {
        return tipRequest;
      }
    }
    return new JsonObject();
  }

  private Future<JsonObject> retrieveTipResponse(String token) {
    Promise<JsonObject> promise = Promise.promise();

    if(token.equalsIgnoreCase("public")) {
      promise.complete(Constants.JSON_PUBLIC_TIP_RESPONSE);
      return promise.future();
    }
    
    JsonObject cacheResponse = tipCache.getOrDefault(token, new JsonObject());
    if (!cacheResponse.isEmpty()) {
      try {
        Instant tokenExpiry = Instant.parse(cacheResponse.getString("expiry"));
        Instant cacheExpiry = Instant.parse(cacheResponse.getString("cache-expiry"));
        Instant now = Instant.now(Clock.systemUTC());
        if (tokenExpiry.isBefore(now)) {
          if (!tipCache.remove(token, cacheResponse)) {
            throw new ConcurrentModificationException("TIP cache premature invalidation");
          }
          LOGGER.debug("Info: Token has expired");
          promise.fail(new Throwable("Token has expired"));
        }
        if (cacheExpiry.isAfter(now)) {
          String extendedCacheExpiry =
              now.plus(Constants.TIP_CACHE_TIMEOUT_AMOUNT, Constants.TIP_CACHE_TIMEOUT_UNIT)
                  .toString();
          JsonObject newCacheEntry = cacheResponse.copy();
          newCacheEntry.put("cache-expiry", extendedCacheExpiry);
          if (!tipCache.replace(token, cacheResponse, newCacheEntry)) {
            throw new ConcurrentModificationException("TIP cache premature invalidation");
          }
          promise.complete(newCacheEntry);
          return promise.future();
        } else {
          if (!tipCache.remove(token, cacheResponse)) {
            throw new ConcurrentModificationException("TIP cache premature invalidation");
          }
        }
      } catch (DateTimeParseException | ConcurrentModificationException e) {
        LOGGER.error(e.getMessage());
      }
    }

    JsonObject body = new JsonObject();
    body.put("token", token);
    webClient.post(443, config.getString(Constants.AUTH_SERVER_HOST), Constants.AUTH_TIP_PATH)
        .expect(ResponsePredicate.JSON).sendJsonObject(body, httpResponseAsyncResult -> {
          if (httpResponseAsyncResult.failed()) {
            promise.fail(httpResponseAsyncResult.cause());
            return;
          }
          HttpResponse<Buffer> response = httpResponseAsyncResult.result();
          if (response.statusCode() != HttpStatus.SC_OK) {
            String errorMessage =
                response.bodyAsJsonObject().getJsonObject("error").getString("message");
            promise.fail(new Throwable(errorMessage));
            return;
          }
          JsonObject responseBody = response.bodyAsJsonObject();
          String cacheExpiry = Instant.now(Clock.systemUTC())
              .plus(Constants.TIP_CACHE_TIMEOUT_AMOUNT, Constants.TIP_CACHE_TIMEOUT_UNIT)
              .toString();
          responseBody.put("cache-expiry", cacheExpiry);
          tipCache.put(token, responseBody);
          promise.complete(responseBody);
        });
    return promise.future();
  }

  /**
   * The open resource validator method.
   * 
   * @param requestIDs A json array of strings which are resource IDs
   * @return A future of a hashmap with the key as the resource ID and a boolean value indicating
   *         open or not
   * 
   *         <p>
   *         There is a known problem with the caching mechanism in this function. If an invalid ID
   *         is received, the CAT is called and the result is not cached. The result is cached only
   *         if a valid ID exists in the CAT. If every ID is stored then the cache can balloon in
   *         size until a server reload. Also a non existent ID will get cached and later if the ID
   *         entry is done on the CAT, it does not get auto propagated to the RS. However, in the
   *         current mechanism, there is a DDoS attack vector where an attacker can send multiple
   *         requests for invalid IDs.
   *         </p>
   */
  private Future<HashMap<String, Boolean>> isOpenResource(JsonArray requestIDs,
      String requestEndpoint) {
    Promise<HashMap<String, Boolean>> promise = Promise.promise();
    HashMap<String, Boolean> result = new HashMap<>();
    if (Constants.OPEN_ENDPOINTS.contains(requestEndpoint)) {
      List<Future> catResponses = new ArrayList<>();
      Promise prom = Promise.promise();
      catResponses.add(prom.future());
      // Check if the resource is already fetched in the cache
      String resID = requestIDs.getString(0);
      if (catrIDCache.contains(resID)) {
        result.put(resID, catrIDCache.get(resID).equalsIgnoreCase("OPEN"));
        prom.complete();
      } else {
        WebClientOptions options =
            new WebClientOptions().setTrustAll(true).setVerifyHost(false).setSsl(true);
        WebClient catWebClient = WebClient.create(vertxObj, options);
        for (Object rID : requestIDs) {
          String resourceID = (String) rID;
          String[] idComponents = resourceID.split("/");
          if (idComponents.length < 4) {
            continue;
          }
          String groupID = (idComponents.length == 4) ? resourceID
              : String.join("/", Arrays.copyOfRange(idComponents, 0, 4));

          String catHost = config.getString("catServerHost");
          int catPort = Integer.parseInt(config.getString("catServerPort"));
          String catPath = Constants.CAT_RSG_PATH;
          LOGGER.debug("Info: Host " + catHost + " Port " + catPort + " Path " + catPath);
          // Check if resourceID is available
          catWebClient.get(catPort, catHost, catPath).addQueryParam("property", "[id]")
              .addQueryParam("value", "[[" + resourceID + "]]").addQueryParam("filter", "[id]")
              .expect(ResponsePredicate.JSON).send(httpResponserIDAsyncResult -> {
                if (httpResponserIDAsyncResult.failed()) {
                  result.put(resourceID, false);
                  prom.fail("Not Found");
                  return;
                }
                HttpResponse<Buffer> rIDResponse = httpResponserIDAsyncResult.result();
                JsonObject rIDResponseBody = rIDResponse.bodyAsJsonObject();

                if (rIDResponse.statusCode() != HttpStatus.SC_OK) {
                  LOGGER.debug("Info: Catalogue Query failed");
                  result.put(resourceID, false);
                  prom.fail("Not Found");
                  return;
                } else if (!rIDResponseBody.getString("status").equals("success")) {
                  LOGGER.debug("Info: Catalogue Query failed");
                  result.put(resourceID, false);
                  prom.fail("Not Found");
                  return;
                } else if (rIDResponseBody.getInteger("totalHits") == 0) {
                  LOGGER.debug("Info: Resource ID invalid : Catalogue item Not Found");
                  result.put(resourceID, false);
                  prom.fail("Not Found");
                  return;
                } else {
                  LOGGER.debug("Info: Resource ID valid : Catalogue item Found");
                  catWebClient.get(catPort, catHost, catPath).addQueryParam("property", "[id]")
                      .addQueryParam("value", "[[" + groupID + "]]")
                      .addQueryParam("filter", "[accessPolicy]").expect(ResponsePredicate.JSON)
                      .send(httpResponseAsyncResult -> {
                        if (httpResponseAsyncResult.failed()) {
                          result.put(resourceID, false);
                          prom.fail("Not Found");
                          return;
                        }
                        HttpResponse<Buffer> response = httpResponseAsyncResult.result();
                        if (response.statusCode() != HttpStatus.SC_OK) {
                          result.put(resourceID, false);
                          prom.fail("Not Found");
                          return;
                        }
                        JsonObject responseBody = response.bodyAsJsonObject();
                        if (!responseBody.getString("status").equals("success")) {
                          result.put(resourceID, false);
                          prom.fail("Not Found");
                          return;
                        }
                        String resourceACL = "SECURE";
                        try {
                          resourceACL = responseBody.getJsonArray("results").getJsonObject(0)
                              .getString("accessPolicy");
                          result.put(resourceID, resourceACL.equals("OPEN"));
                          catCache.put(groupID, resourceACL);
                          catrIDCache.put(resourceID, resourceACL);
                          LOGGER.debug("Info: Group ID valid : Catalogue item Found");
                        } catch (IndexOutOfBoundsException ignored) {
                          LOGGER.error(ignored.getMessage());
                          LOGGER.debug(
                              "Info: Group ID invalid : Empty response in results from Catalogue");
                        }
                        prom.complete();
                      });
                }
              });
        }
      }
      CompositeFuture.all(catResponses).onSuccess(compositeFuture -> promise.complete(result))
          .onFailure(failedhandler -> {
            LOGGER.debug("Info: TIP / Cat Failed");
            JsonObject failedresult = new JsonObject();
            failedresult.put("status", "Not Found");
            promise.fail(failedresult.toString());
          });

    } else {
      result.put("Closed End Point", true);
      promise.complete();
    }

    return promise.future();
  }

  private Future<JsonObject> validateAccess(JsonObject result, HashMap<String, Boolean> catResponse, JsonObject authenticationInfo,
      JsonObject userRequest) {

    Promise<JsonObject> promise = Promise.promise();

    LOGGER.debug("Info: TIP response is " + result);
    LOGGER.debug("Info: Authentication Info is " + authenticationInfo);
    LOGGER.debug("Info: catResponse is " + catResponse);
    String requestEndpoint = authenticationInfo.getString("apiEndpoint");
    String requestMethod = authenticationInfo.getString("method");

    LOGGER.debug("Info: requested endpoint :" + requestEndpoint);

    // 1. Check the API requested.
    if (Constants.OPEN_ENDPOINTS.contains(requestEndpoint)) {
      JsonObject response = new JsonObject();
      LOGGER.info(Constants.OPEN_ENDPOINTS);

      // 1.1. Check with catalogue if resource is open or secure.
      // 1.2. If open respond success.
      // 1.3. If closed, check if auth response has access to the requested resource.

      LOGGER.debug("Info: TIP response is " + result);

      String allowedID = result.getJsonArray("request").getJsonObject(0).getString("id");
      String allowedGroupID = allowedID.substring(0, allowedID.lastIndexOf("/"));

      LOGGER.debug("Info: allowedID is " + allowedID);
      LOGGER.debug("Info: allowedGroupID is " + allowedGroupID);
      
      LOGGER.debug("Info: userRequest is " + userRequest);
      
      String requestedID = userRequest.getJsonArray("ids").getString(0);
      String requestedGroupID = requestedID.substring(0, requestedID.lastIndexOf("/"));

      LOGGER.debug("Info: requestedID is " + requestedID);
      LOGGER.debug("Info: requestedGroupID is " + requestedGroupID);
      
      // Check if resource is available in Catalogue
      if (catResponse.isEmpty()) {
        LOGGER.debug("Info: No such catalogue item");
        response.put("item", "Not Found");
        promise.fail(response.toString());
      } else {
        if (catResponse.get(requestedID)) {
          LOGGER.debug("Info: Catalogue item is OPEN");
          response.put(Constants.JSON_CONSUMER, result.getString(Constants.JSON_CONSUMER));
          promise.complete(response);
        } else {
          // Check if the token has access to the requestedID
          LOGGER.debug("Info: Catalogue item is SECURE");
          if (requestedGroupID.equalsIgnoreCase(allowedGroupID)) {
            LOGGER.debug("Info: Catalogue item is SECURE and User has ACCESS");
            response.put(Constants.JSON_CONSUMER, result.getString(Constants.JSON_CONSUMER));
            promise.complete(response);
          } else {
            LOGGER.debug("Info: Catalogue item is SECURE and User does not have ACCESS");
            response.put(Constants.JSON_CONSUMER, result.getString(Constants.JSON_PUBLIC_CONSUMER));
            promise.fail(response.toString());
          }
        }
      }
      
    } else if (Constants.ADAPTER_ENDPOINT.contains(requestEndpoint)) {
      LOGGER.debug("Info: Requested access for " + requestEndpoint);
      JsonArray tipresult = result.getJsonArray("request");
      JsonObject tipresponse = tipresult.getJsonObject(0);
      LOGGER.debug("Info: Allowed APIs " + tipresponse);
      JsonArray allowedAPIs = tipresponse.getJsonArray("apis");
      int total = allowedAPIs.size();
      boolean allowedAccess = false;
      for (int i = 0; i < total; i++) {
        if (Constants.ADAPTER_ENDPOINT.contains(allowedAPIs.getString(i))) {
          LOGGER.debug("Info: Success :: User has access to " + requestEndpoint + " API");
          allowedAccess = true;
          break;
        }
      }
      if (allowedAccess) {
        String providerID = tipresponse.getString("id");
        String adapterID = providerID.substring(0, providerID.lastIndexOf("/"));
        String[] id = providerID.split("/");
        String providerSHA = id[0] + "/" + id[1];
        LOGGER.debug("Info: Success :: Provider SHA is " + providerSHA);
        if (requestMethod.equalsIgnoreCase("POST")) {
          String resourceGroup = userRequest.getString("resourceGroup");
          String resourceServer = userRequest.getString("resourceServer");
          System.out.println(providerID);
          System.out.println(resourceGroup);
          System.out.println(resourceServer);
          if (providerID.contains(resourceServer + "/" + resourceGroup)) {
            LOGGER.info(
                "Success :: Has access to " + requestEndpoint + " API and Adapter " + adapterID);
            result.put("provider", providerSHA);
            promise.complete(result);
          } else {
            LOGGER.debug("Info: Failure :: Has access to " + requestEndpoint + " API but not for Adapter "
                + adapterID);
            promise.fail(result.toString());
          }
        } else {
          String requestId = authenticationInfo.getString("id");
          if (requestId.contains(adapterID)) {
            LOGGER.info(
                "Success :: Has access to " + requestEndpoint + " API and Adapter " + requestId);
            promise.complete(result);
          } else {
            LOGGER.debug("Info: Failure :: Has access to " + requestEndpoint + " API but not for Adapter "
                + requestId);
            promise.fail(result.toString());
          }
        }
      } else {
        LOGGER.debug("Info: Failure :: No access to " + requestEndpoint + " API");
        promise.fail(result.toString());
      }
    } else if (Constants.SUBSCRIPTION_ENDPOINT.contains(requestEndpoint)) {
      LOGGER.debug("Info: Requested access for " + requestEndpoint);
      JsonArray tipresult = result.getJsonArray("request");
      JsonObject tipresponse = tipresult.getJsonObject(0);
      LOGGER.debug("Info: Allowed APIs " + tipresponse);
      JsonArray allowedAPIs = tipresponse.getJsonArray("apis");
      int total = allowedAPIs.size();
      boolean allowedAccess = false;
      for (int i = 0; i < total; i++) {
        if (Constants.SUBSCRIPTION_ENDPOINT.contains(allowedAPIs.getString(i))) {
          LOGGER.debug("Info: Success :: User has access to API");
          allowedAccess = true;
          break;
        }
      }

      if (allowedAccess) {
        if (requestMethod.equalsIgnoreCase("POST")) {
          String allowedId = tipresponse.getString("id");
          String id = userRequest.getJsonArray("entities").getString(0);
          String requestedId = id.substring(0, id.lastIndexOf("/"));

          if (allowedId.contains(requestedId)) {
            LOGGER.debug("Info: Success :: Has access to " + requestEndpoint + " API and entity " + id);
            promise.complete(result);
          } else {
            LOGGER.info(
                "Failure :: Has access to " + requestEndpoint + " API but not for entity " + id);
            promise.fail(result.toString());
          }
        } else if (requestMethod.equalsIgnoreCase("PUT")
            || requestMethod.equalsIgnoreCase("PATCH")) {
          String requestId = authenticationInfo.getString("id");
          String email = result.getString("consumer");
          String allowedId = tipresponse.getString("id");
          String id = userRequest.getJsonArray("entities").getString(0);
          String requestedId = id.substring(0, id.lastIndexOf("/"));
          if (requestId.contains(Util.getSha(email))) {
            LOGGER.debug("Info: Success :: Has access to " + requestEndpoint + " API and Subscription ID " + requestId);
            if (allowedId.contains(requestedId)) {
              LOGGER.debug("Info: Success :: Has access to " + requestEndpoint + " API and Subscription ID " + requestId
                  + " and entity " + id);
              promise.complete(result);
            } else {
              LOGGER.debug("Info: Failure :: Has access to " + requestEndpoint + " API and Subscription ID " + requestId
                  + " but not for entity " + id);
              promise.fail(result.toString());
            }
          } else {
            LOGGER.debug("Info: Failure");
            promise.fail(result.toString());
          }
        } else {
          String requestId = authenticationInfo.getString("id");
          String email = result.getString("consumer");
          if (requestId.contains(Util.getSha(email))) {
            LOGGER.debug("Info: Success :: Has access to " + requestEndpoint + " API and Subscription ID " + requestId);
            promise.complete(result);
          } else {
            LOGGER.info(
                "Failure :: Has access to " + requestEndpoint + " API but not for Subscription ID " + requestId);
            promise.fail(result.toString());
          }
        }
      } else {
        LOGGER.debug("Info: Failure :: No access to " + requestEndpoint + " API");
        promise.fail(result.toString());
      }
    } else if (Constants.MANAGEMENT_ENDPOINTS.contains(requestEndpoint)) {
      LOGGER.debug("Info: Requested access for " + requestEndpoint);
      JsonArray tipresult = result.getJsonArray("request");
      JsonObject tipresponse = tipresult.getJsonObject(0);
      LOGGER.debug("Info: Allowed APIs " + tipresponse);
      JsonArray allowedAPIs = tipresponse.getJsonArray("apis");
      int total = allowedAPIs.size();
      boolean allowedAccess = false;
      String providerID = tipresponse.getString("id");
      String[] id = providerID.split("/");
      String providerSHA = id[0] + "/" + id[1];
      String email = result.getString("consumer");
      if (providerSHA.equalsIgnoreCase(Constants.JSON_IUDX_ADMIN_SHA)) {
        for (int i = 0; i < total; i++) {
          if (Constants.MANAGEMENT_ENDPOINT.contains(allowedAPIs.getString(i))) {
            LOGGER.debug("Info: Success :: User " + email + " has access to API");
            allowedAccess = true;
            break;
          }
        }
      }

      if (allowedAccess) {
        LOGGER.debug("Info: Success :: Has access to " + requestEndpoint + " API");
        promise.complete(result);
      } else {
        LOGGER.debug("Info: Failure :: No access to " + requestEndpoint + " API");
        promise.fail(result.toString());
      }
    }
    return promise.future();
  }
}
