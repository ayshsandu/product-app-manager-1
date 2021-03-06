/*
*Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
*WSO2 Inc. licenses this file to you under the Apache License,
*Version 2.0 (the "License"); you may not use this file except
*in compliance with the License.
*You may obtain a copy of the License at
*
*http://www.apache.org/licenses/LICENSE-2.0
*
*Unless required by applicable law or agreed to in writing,
*software distributed under the License is distributed on an
*"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
*KIND, either express or implied.  See the License for the
*specific language governing permissions and limitations
*under the License.
*/

package org.wso2.appmanager.integration.restapi.utils;


import com.jayway.jsonpath.JsonPath;
import com.sun.jersey.api.client.ClientResponse;
import exception.AppManagerIntegrationTestException;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.RandomStringUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.cxf.jaxrs.impl.ResponseImpl;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.wso2.appmanager.integration.restapi.RESTAPITestConstants;
import org.wso2.carbon.automation.engine.exceptions.AutomationFrameworkException;
import org.wso2.carbon.automation.test.utils.http.client.HttpRequestUtil;
import org.wso2.carbon.automation.test.utils.http.client.HttpResponse;

import javax.ws.rs.core.Response;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This class is used as a util class to test the REST API.
 */
public class RESTAPITestUtil {

    private static final Log log = LogFactory.getLog(RESTAPITestUtil.class);
    private DataDrivenTestUtils dataDrivenTestUtils = new DataDrivenTestUtils();

    //this is the map to store the preserved attributes if there are any
    private HashMap<String, String> preservedAttributes = new HashMap<String, String>();

    /**
     * This method is used to test a given scenario using the REST API
     *
     * @param configFilePath location of the data file
     * @param gatewayURL     gateway URL
     * @param keyMangerURL   key manager URL
     * @return true if all the scenarios in the current test is successful, otherwise false
     */
    public boolean testRestAPI(String configFilePath, String gatewayURL, String keyMangerURL) {

        //this is the boolean which indicates the success or failure of the current test
        boolean isTestSuccess = false;
        String currentRestApiLoggingString = "";
        String outputText = "";

        try {

            String configData = getConfigurations(configFilePath);
            Map<String, String> dataMap = registerOAuthApplication(keyMangerURL);
            String accessToken;
            JSONObject testScenario = new JSONObject(configData);
            JSONArray array = testScenario.getJSONArray(RESTAPITestConstants.JSON_ROOT_ELEMENT);
            int arrayLength = array.length();

            //go through each section of the JSON string and fetch data
            for (int i = 0; i < arrayLength; i++) {

                JSONObject configObject = (JSONObject) array.get(i);
                JSONObject initializationConfigurationObject =
                        configObject.getJSONObject(RESTAPITestConstants.INITIALIZATION_SECTION);
                String scope = initializationConfigurationObject.get(RESTAPITestConstants.SCOPE_ELEMENT).toString();

                if (dataMap == null || dataMap.isEmpty()) {
                    //if the data map is null or empty, that means it has failed to register OAuth application
                    log.error("Failed to register OAuth application to test the REST API.");
                    isTestSuccess = false;
                    //stop the rest of the iterations of the loop if it has failed to register OAuth application
                    break;
                }
                //get the access token to send the request
                accessToken = generateOAuthAccessToken(scope, dataMap, gatewayURL);

                if (accessToken == null) {
                    //if the access token is null, that means it has failed to generate OAuth access token
                    log.error("Failed to generate OAuth access token to test the REST API.");
                    isTestSuccess = false;
                    //stop the rest of the iterations of the loop if the test has failed due to accessToken error
                    break;
                }

                JSONObject dataConfigurationObject = configObject.getJSONObject(RESTAPITestConstants.DATA_SECTION);
                String method = dataConfigurationObject.get(RESTAPITestConstants.METHOD_ELEMENT).toString();
                //take the resource URL from the data file (initial URL, might contain space for parameters)
                String parametrizedResourceURL = keyMangerURL +
                        dataConfigurationObject.get(RESTAPITestConstants.URL_ELEMENT).toString();
                currentRestApiLoggingString += "REST API URL - \'" + parametrizedResourceURL +
                        "\' , Method - \'" + method + "\'";
                log.info("Test starting :- " + currentRestApiLoggingString);
                //replace the parameters with the actual vales if there are any, and construct the actual URL
                String actualResourceURL = replaceParameterPatternWithValues
                                                (parametrizedResourceURL, RESTAPITestConstants.URL_REGEX);
                Map<String, String> queryParameters = new HashMap<String, String>();
                String queryParameterText =
                        dataConfigurationObject.get(RESTAPITestConstants.QUERY_PARAMETERS).toString();

                //fetch the query parameters if there are any, and put them into the query parameter map
                if (!queryParameterText.isEmpty()) {
                    Iterator queryParamIterator = dataConfigurationObject.getJSONObject
                            (RESTAPITestConstants.QUERY_PARAMETERS).keys();
                    while (queryParamIterator.hasNext()) {
                        String key = queryParamIterator.next().toString();
                        String value = dataConfigurationObject.getJSONObject
                                (RESTAPITestConstants.QUERY_PARAMETERS).getString(key);
                        queryParameters.put(key, value);
                    }
                }

                Map<String, String> requestHeaders = new HashMap<String, String>();
                String requestHeaderText = dataConfigurationObject.get(RESTAPITestConstants.REQUEST_HEADERS).toString();

                //fetch the request headers if there are any, and put them into the request header map
                if (!requestHeaderText.isEmpty()) {
                    Iterator requestHeaderIterator = dataConfigurationObject.getJSONObject
                            (RESTAPITestConstants.REQUEST_HEADERS).keys();
                    while (requestHeaderIterator.hasNext()) {
                        String key = requestHeaderIterator.next().toString();
                        String value = dataConfigurationObject.
                                getJSONObject(RESTAPITestConstants.REQUEST_HEADERS).getString(key);

                        //set the Auth header according to the required manner (i.e - separated with a space)
                        if (RESTAPITestConstants.AUTHORIZATION_KEY.equalsIgnoreCase(key)) {
                            value = value.concat(" " + accessToken);
                        }
                        requestHeaders.put(key, value);
                    }
                }

                //take the initial payload from the data file (might contain space for parameters)
                String parametrizedRequestPayload =
                        dataConfigurationObject.get(RESTAPITestConstants.REQUEST_PAYLOAD).toString();
                //replace the parameters with actual vales if there are any, and construct the actual request payload
                String actualRequestPayload = replaceParameterPatternWithValues
                                                (parametrizedRequestPayload, RESTAPITestConstants.PAYLOAD_REGEX);
                String responseHeaderText =
                        dataConfigurationObject.get(RESTAPITestConstants.RESPONSE_HEADERS).toString();
                Map<String, String> responseHeaders = new HashMap<String, String>();

                //fetch the response headers if there are any, and put them into the response header map
                if (!responseHeaderText.isEmpty()) {
                    Iterator responseHeaderIterator = dataConfigurationObject.getJSONObject
                            (RESTAPITestConstants.RESPONSE_HEADERS).keys();
                    while (responseHeaderIterator.hasNext()) {
                        String key = responseHeaderIterator.next().toString();
                        String value = dataConfigurationObject.getJSONObject
                                (RESTAPITestConstants.RESPONSE_HEADERS).getString(key);
                        responseHeaders.put(key, value);
                    }
                }

                String responsePayload = dataConfigurationObject.get(RESTAPITestConstants.RESPONSE_PAYLOAD).toString();
                String cookie = null;
                int actualStatusCode = 0;

                Object responseObjOfHttpCall = dataDrivenTestUtils.sendRequestToRESTAPI
                        (method, actualResourceURL, queryParameters, requestHeaders, actualRequestPayload, cookie);
                if (responseObjOfHttpCall instanceof Response) {
                    Response responseOfHttpCall = (Response)responseObjOfHttpCall;
                    outputText = ((ResponseImpl) responseOfHttpCall).readEntity(String.class);
                    actualStatusCode = responseOfHttpCall.getStatus();
                } else if (responseObjOfHttpCall instanceof ClientResponse) {
                    ClientResponse responseOfHttpCall = (ClientResponse)responseObjOfHttpCall;
                    outputText = responseOfHttpCall.getEntity(String.class);
                    actualStatusCode = responseOfHttpCall.getStatus();
                }

                //put the values (to the map) that should be preserved from the response of the current request,
                if (!configObject.isNull(RESTAPITestConstants.PRESERVE_LIST)) {
                    JSONArray preserveListArray = configObject.getJSONArray(RESTAPITestConstants.PRESERVE_LIST);
                    if (preserveListArray != null && preserveListArray.length() > 0) {
                        for (int j = 0; j < preserveListArray.length(); j++) {
                            String parameterName = preserveListArray.getJSONObject(j).
                                    getString(RESTAPITestConstants.PRESERVED_ATTRIBUTE_NAME);
                            String parameterValue = new JSONObject(outputText).get(preserveListArray.getJSONObject(j).
                                    getString(RESTAPITestConstants.RESPONSE_LOCATION)).toString();
                            if (preserveListArray.getJSONObject(j).has(RESTAPITestConstants.RESPONSE_SPLIT_ON)) {
                                parameterValue = parameterValue.split(preserveListArray.getJSONObject(j).
                                        getString(RESTAPITestConstants.RESPONSE_SPLIT_ON))[1];
                            }
                            preservedAttributes.put(parameterName, parameterValue);
                        }
                    }
                }

                int expectedStatusCode = configObject.getJSONObject(RESTAPITestConstants.ASSERT_SECTION).
                        getJSONObject(RESTAPITestConstants.HEADER_ASSERTS).getInt(RESTAPITestConstants.STATUS_CODE);

                //the test is successful only if actualStatusCode is same as expectedStatusCode
                if (actualStatusCode == expectedStatusCode) {
                    isTestSuccess = true;
                } else {
                    isTestSuccess = false;
                }

                //checking for body-asserts
                JSONArray bodyAsserts = configObject.getJSONObject(RESTAPITestConstants.ASSERT_SECTION).
                        getJSONArray(RESTAPITestConstants.BODY_ASSERTS);

                if (bodyAsserts != null && bodyAsserts.length() > 0) {
                    if (!StringUtils.isBlank(outputText)) {
                        for (int j = 0; j < bodyAsserts.length(); j++) {
                            JSONObject bodyAssert = (JSONObject) bodyAsserts.get(j);
                            String jsonPath = bodyAssert.getString(RESTAPITestConstants.BODY_ASSERTS_JSONPATH);
                            Object actualValue = JsonPath.read(outputText, jsonPath);
                            if (bodyAssert.has(RESTAPITestConstants.BODY_ASSERTS_VALUE)) {
                                Object expectedValue = bodyAssert.get(RESTAPITestConstants.BODY_ASSERTS_VALUE);
                                if (!expectedValue.equals(actualValue)) {
                                    log.error("Json path actual value mismatches with expected value. jsonPath: \""
                                            + jsonPath + "\", expected: <" + expectedValue.getClass().getSimpleName()
                                            + ">" + expectedValue + ", actual value: <" + actualValue.getClass()
                                            .getSimpleName() + ">" + actualValue + ", response payload: " + outputText);
                                    isTestSuccess = false;
                                    break;
                                }
                            } else if (bodyAssert.has(RESTAPITestConstants.BODY_ASSERTS_REGEX)) {
                                String regex = bodyAssert.getString(RESTAPITestConstants.BODY_ASSERTS_REGEX);
                                if (!Pattern.matches(regex, actualValue.toString())) {
                                    log.error("Json path actual value mismatches with provided regex. jsonPath: \""
                                            + jsonPath + "\", regex: \"" + regex + "\", actual value: \"" + actualValue
                                            + "\", response payload: " + outputText);
                                    isTestSuccess = false;
                                    break;
                                }
                            }
                        }
                    } else {
                        log.error("A body-assert is defined but no response payload found.");
                        isTestSuccess = false;
                    }
                }

                //if the current test fails no need to run the rest of the scenario, so break and return false
                if (!isTestSuccess) {
                    log.info("Test failed :- " + currentRestApiLoggingString);
                    break;
                }
                log.info("Test passed :- " + currentRestApiLoggingString);
                currentRestApiLoggingString = "";
            }

        } catch (AppManagerIntegrationTestException integrationTestException) {
            //if an error occurs while sending request to the REST API, the test fails
            log.error("Error occurred in sending request to the REST API. " + currentRestApiLoggingString
                    , integrationTestException);
            isTestSuccess = false;

        } catch (JSONException e) {
            //if an error occurs while parsing the data in JSON file, the test fails
            log.error("Error occurred in parsing the data in JSON file. JSON :- " + outputText, e);
            isTestSuccess = false;
        }
        return isTestSuccess;
    }

    /**
     * This method reads the data/configurations from the given file
     *
     * @param fileLocation location of the data file
     * @return a string that contains the content in the file
     * @throws AppManagerIntegrationTestException if it fails to read the data file
     */
    private String getConfigurations(String fileLocation) throws AppManagerIntegrationTestException {

        BufferedReader bufferedReader = null;
        try {
            bufferedReader = new BufferedReader(new FileReader(new File(fileLocation)));
            String line;
            StringBuilder stringBuilder = new StringBuilder();
            while ((line = bufferedReader.readLine()) != null) {
                stringBuilder.append(line);
            }
            return stringBuilder.toString();
        } catch (IOException ioE) {
            log.error("IOException when reading configuration data:" + fileLocation, ioE);
            throw new AppManagerIntegrationTestException("Error in reading data from config file.", ioE);
        } finally {
            if (bufferedReader != null) {
                try {
                    bufferedReader.close();
                } catch (IOException e) {
                    //we only log the exception because the required data has been fetched
                    log.warn("Error when closing the buffered reader which used to reed the file:" + fileLocation +
                            ". Error:" + e.getMessage());
                }
            }
        }
    }

    /**
     * This method will plug the actual value for a parametrized string, by making use of a given regular expression
     *
     * @param parametrizedText initial text, might contain space for parameters
     * @param regex            regular expression to identify the parameter location
     * @return actual text where parameters are replaced with the corresponding values
     */
    private String replaceParameterPatternWithValues(String parametrizedText, String regex) {

        String actualTextWithValues = parametrizedText;

        if (parametrizedText != null && regex != null && regex.length() > 1) {
            //get the pattern for dynamically generated texts
            Pattern parameterPattern = Pattern.compile(regex);
            Matcher matcher = parameterPattern.matcher(parametrizedText);

            //if a match is found, then fetch the applicable value from the preserved attribute list
            while (matcher.find()) {
                String parameterName = matcher.group(1);
                //construct the final text with the values fetched from the preserved attribute list
                String template = Character.toString(regex.charAt(1)) +
                        parameterName + Character.toString(regex.charAt(regex.length() - 1));
                actualTextWithValues = parametrizedText.replace(template, preservedAttributes.get(parameterName));
                //If the parametrizedText contains more than one parameter parametrizedText should be update with
                //the already replaced test.
                parametrizedText = actualTextWithValues;
            }
        }
        //if patterns are found, then this returns the text with the values fetched from the preserved attribute list
        return actualTextWithValues;
    }

    /**
     * This method generates OAuth access token to invoke the REST API
     *
     * @param scope      scope required to create the token
     * @param dataMap    map which contains the consumer key and secret
     * @param gatewayUrl url of the gateway
     * @return generating access token
     * @throws AppManagerIntegrationTestException if an error occurs while generating access token
     */
    private String generateOAuthAccessToken(String scope, Map<String, String> dataMap, String gatewayUrl)
            throws AppManagerIntegrationTestException {

        try {
            String consumeKey = dataMap.get(RESTAPITestConstants.CONSUMER_KEY);
            String consumerSecret = dataMap.get(RESTAPITestConstants.CONSUMER_SECRET);
            String messageBody = RESTAPITestConstants.OAUTH_MESSAGE_BODY + scope;
            URL tokenEndpointURL = new URL(gatewayUrl + RESTAPITestConstants.TOKEN_ENDPOINT_SUFFIX);
            HashMap<String, String> accessKeyMap = new HashMap<String, String>();

            //concat consumeKey and consumerSecret and make the authenticationHeader to get access token
            String authenticationHeader = consumeKey + ":" + consumerSecret;
            byte[] encodedBytes = Base64.encodeBase64(authenticationHeader.getBytes("UTF-8"));
            accessKeyMap.put(RESTAPITestConstants.AUTHORIZATION_KEY, "Basic " + new String(encodedBytes, "UTF-8"));
            HttpResponse tokenGenerateResponse = HttpRequestUtil.doPost(tokenEndpointURL, messageBody, accessKeyMap);
            JSONObject tokenGenJsonObject = new JSONObject(tokenGenerateResponse);
            String accessToken = new JSONObject(tokenGenJsonObject.get(RESTAPITestConstants.DATA_SECTION).toString())
                    .get(RESTAPITestConstants.ACCESS_TOKEN_TEXT).toString();

            if (accessToken != null) {
                return accessToken;
            }

        } catch (MalformedURLException malformedURLException) {
            log.error("Error in getting the URL of token endpoint.", malformedURLException);
            throw new AppManagerIntegrationTestException
                    ("Error in getting the URL of token endpoint.", malformedURLException);
        } catch (UnsupportedEncodingException unsupportedEncodingException) {
            log.error("Message header encoding was unsuccessful using UTF-8.", unsupportedEncodingException);
            throw new AppManagerIntegrationTestException
                    ("Message header encoding was unsuccessful using UTF-8.", unsupportedEncodingException);
        } catch (AutomationFrameworkException automationFrameworkException) {
            log.error("Error in sending the request to token endpoint.", automationFrameworkException);
            throw new AppManagerIntegrationTestException
                    ("Error in sending the request to token endpoint.", automationFrameworkException);
        } catch (JSONException e) {
            log.error("Error in parsing JSON content in response from token endpoint.", e);
            throw new AppManagerIntegrationTestException
                    ("Error in parsing JSON content in response from token endpoint.", e);
        }
        return null;
    }

    /**
     * This method is used to register OAuth Application
     *
     * @param keyMangerUrl url of the key manger
     * @return map which contains the consumer key and secret
     * @throws AppManagerIntegrationTestException if it fails to register OAuth Application
     */
    private Map<String, String> registerOAuthApplication(String keyMangerUrl)
            throws AppManagerIntegrationTestException {

        String dcrEndpointURL = keyMangerUrl + RESTAPITestConstants.CLIENT_REGISTRATION_URL;

        //use a random name for client to avoid conflicts in application(s)
        String randomClientName = RandomStringUtils.randomAlphabetic(5);
        String applicationRequestBody = "{\n" +
                "\"callbackUrl\": \"www.google.lk\",\n" +
                "\"clientName\": \"" + randomClientName + "\",\n" +
                "\"tokenScope\": \"Production\",\n" +
                "\"owner\": \"admin\",\n" +
                "\"grantType\": \"password refresh_token\",\n" +
                "\"saasApp\": true\n" +
                "}";

        Map<String, String> dcrRequestHeaders = new HashMap<String, String>();
        Map<String, String> dataMap = new HashMap<String, String>();

        try {

            //Basic Auth header is used for only to get token
            byte[] encodedBytes = Base64.encodeBase64(RESTAPITestConstants.BASIC_AUTH_HEADER.getBytes("UTF-8"));
            dcrRequestHeaders.put(RESTAPITestConstants.AUTHORIZATION_KEY, "Basic " + new String(encodedBytes, "UTF-8"));

            //Set content type as its mandatory
            dcrRequestHeaders.put(RESTAPITestConstants.CONTENT_TYPE, RESTAPITestConstants.APPLICATION_JSON_CONTENT);
            JSONObject clientRegistrationResponse = new JSONObject
                    (HttpRequestUtil.doPost(new URL(dcrEndpointURL), applicationRequestBody, dcrRequestHeaders));
            String consumerKey = new JSONObject(clientRegistrationResponse.getString(RESTAPITestConstants.DATA_SECTION))
                    .get(RESTAPITestConstants.CLIENT_ID).toString();
            String consumerSecret = new JSONObject
                    (clientRegistrationResponse.getString(RESTAPITestConstants.DATA_SECTION)).
                    get(RESTAPITestConstants.CLIENT_SECRET).toString();

            //give 2 second duration to create consumer key and consumer secret
            Thread.sleep(2000);
            dataMap.put(RESTAPITestConstants.CONSUMER_KEY, consumerKey);
            dataMap.put(RESTAPITestConstants.CONSUMER_SECRET, consumerSecret);

        } catch (UnsupportedEncodingException unsupportedEncodingException) {
            log.error("Header encoding was unsuccessful while registering application.", unsupportedEncodingException);
            throw new AppManagerIntegrationTestException
                    ("Header encoding was unsuccessful while registering application.", unsupportedEncodingException);
        } catch (MalformedURLException malformedURLException) {
            log.error("Error in getting the DCR endpoint URL.", malformedURLException);
            throw new AppManagerIntegrationTestException
                    ("Error in getting the DCR endpoint URL.", malformedURLException);
        } catch (AutomationFrameworkException automationFrameworkException) {
            log.error("Error in sending request to the DCR endpoint.", automationFrameworkException);
            throw new AppManagerIntegrationTestException
                    ("Error in sending request to the DCR endpoint.", automationFrameworkException);
        } catch (JSONException e) {
            log.error("Error in parsing JSON to get consumer key/secret.", e);
            throw new AppManagerIntegrationTestException("Error in parsing JSON to get consumer key/secret.", e);
        } catch (InterruptedException interruptedException) {
            log.error("Thread interrupted while waiting to get consumer key/secret.", interruptedException);
            throw new AppManagerIntegrationTestException
                    ("Thread interrupted while waiting to get consumer key/secret.", interruptedException);
        }
        return dataMap;
    }

}
