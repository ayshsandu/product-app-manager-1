/*
 * Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 * 
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.appmanager.integration.utils;

import org.json.JSONObject;
import org.wso2.appmanager.integration.utils.bean.AppCreateRequest;
import org.wso2.carbon.automation.test.utils.http.client.HttpRequestUtil;
import org.wso2.carbon.automation.test.utils.http.client.HttpResponse;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

public class APPMPublisherRestClient {
    private String backEndUrl;
    private Map<String, String> requestHeaders = new HashMap<String, String>();

    public APPMPublisherRestClient(String backEndUrl) throws MalformedURLException {
        this.backEndUrl = backEndUrl;
        if (requestHeaders.get("Content-Type") == null) {
            this.requestHeaders.put("Content-Type", "application/json");
        }
    }

    /**
     * logs in to publisher.
     *
     * @param userName String.
     * @param password String.
     * @return HttpResponse httpResponse.
     * @throws Exception on errors.
     */
    public HttpResponse login(String userName, String password) throws Exception {
        HttpResponse response = HttpRequestUtil.doPost(new URL(backEndUrl
                                                        + "/publisher/api/authenticate?action=login"
                                                        + "&username="
                                                        + userName
                                                        + "&password="
                                                        + password), "{}",
                                                        requestHeaders);
        if (response.getResponseCode() == 200) {
            String session = getSession(response.getHeaders());
            if (session == null) {
                throw new Exception("No session cookie found with response");
            }
            setSession(session);
            return response;
        } else {
            throw new Exception("User Login failed! " + response.getData());
        }

    }

    /**
     * Create New Web application.
     * @param appRequest AppCreateRequest.
     * @return httpResponse HttpResponse.
     * @throws Exception on errors.
     */
    public HttpResponse webAppCreate(AppCreateRequest appRequest) throws Exception {
        checkAuthentication();
        String appDescription = "default app description for " + appRequest.getOverview_name();
        HttpResponse httpResponse = addPoicyGroup(appDescription);
        String httpResponseData = httpResponse.getData();
        if (httpResponse.getResponseCode() == 200) {
            JSONObject responseData = new JSONObject(httpResponseData);
            String response = responseData.getString("response");
            JSONObject responseObject = new JSONObject(response);

            //Retrieve the policy group id.
            String policyId = responseObject.getString("id");
            String policyGroupId = "[" + policyId + "]";

            //Set new policy Id to AppCreateRequest;
            appRequest.setUritemplate_policyGroupId0(policyId);
            appRequest.setUritemplate_policyGroupId1(policyId);
            appRequest.setUritemplate_policyGroupId2(policyId);
            appRequest.setUritemplate_policyGroupId3(policyId);
            appRequest.setUritemplate_policyGroupId4(policyId);
            appRequest.setUritemplate_policyGroupIds(policyGroupId);

            HttpResponse appCreateResponse = createApp(appRequest);
            if (appCreateResponse.getResponseCode() == 200) {
                return appCreateResponse;
            } else {
                throw new Exception("Error occurred while new web app creating! "
                                            + appCreateResponse.getData());
            }
        } else {
            throw new Exception("Error occurred while policy adding! " + httpResponseData);
        }
    }

    /**
     * Add policy group before create the application.
     * @param policyDesc String.
     * @return httpResponse.
     * @throws Exception on errors.
     */
    private HttpResponse addPoicyGroup(String policyDesc) throws Exception {
        requestHeaders.put("Content-Type", "application/x-www-form-urlencoded");

        String payload = "policyGroupName=Default&throttlingTier=Unlimited&userRoles"
                    + "=&anonymousAccessToUrlPattern=false&objPartialMappings"
                    + "=[]&policyGroupDesc="
                    + policyDesc;

        HttpResponse response = HttpRequestUtil.doPost(new URL(backEndUrl
                                              + "/publisher/api/entitlement/policy/partial/"
                                              + "policyGroup/save"), payload, requestHeaders);
        return response;

    }

    /**
     * creating an application
     *
     * @param appRequest - to create the payload
     * @return response
     * @throws Exception
     */
    private HttpResponse createApp(AppCreateRequest appRequest) throws Exception {
        String payload = appRequest.generateRequestParameters();
        String roles = appRequest.getRoles();
        this.requestHeaders.put("Content-Type", "application/x-www-form-urlencoded");
        HttpResponse response =
                HttpRequestUtil.doPost(new URL(backEndUrl +
                                                       "/publisher/asset/webapp"), payload,
                                       requestHeaders);
        if (response.getResponseCode() == 200) {
            JSONObject jsonObject = new JSONObject(response.getData());
            String appId = (String) jsonObject.get("id");

            if (!roles.equals("")) {
                this.addRole(roles, appId);
            }

            String tag = appRequest.getTags();
            if (!tag.equals("")) {
                this.addNewTag(appId, tag);
            }
            return response;
        } else {
            throw new Exception("App creation failed> " + response.getData());
        }
    }

    /**
     * this method adds the roles to an application
     *
     * @param roles
     * @param appId
     * @return
     * @throws Exception
     */
    private HttpResponse addRole(String roles, String appId) throws Exception {
        String role = roles;
        this.requestHeaders.put("Content-Type", "application/json");
        HttpResponse response =
                HttpUtil.doPost(new URL(backEndUrl + "/publisher/asset/webapp/id/" +
                                                appId + "/permissions"),
                                "[{\"role\":\"" + role +
                                        "\",\"permissions\":[\"GET\",\"PUT\",\"DELETE\"," +
                                        "\"AUTHORIZE\"]}]",
                                requestHeaders);
        if (response.getResponseCode() == 200) {

            return response;
        } else {
            throw new Exception("Add role failed> " + response.getData());
        }
    }

    /**
     * publish an application which is in created state
     *
     * @param appId - application id
     * @return -response
     * @throws Exception
     */
    public HttpResponse publishWebApp(String appId) throws Exception {

        //Submit the app for review
        changeState(appId, "Submit for Review");
        //Approve the app
        changeState(appId, "Approve");
        //Publish the app
        HttpResponse response = changeState(appId, "Publish");
        return response;
    }

    /**
     * change the life cycle state from current state to next state	 *
     *
     * @param appId
     * @param toState
     * @return
     * @throws Exception
     */
    public HttpResponse changeState(String appId, String toState) throws Exception {
        this.requestHeaders.put("Content-Type", "");
        String encodedState = toState.replaceAll(" ", "%20");

        HttpResponse response =
                HttpUtil.doPut(new URL(backEndUrl + "/publisher/api/lifecycle/" +
                                               encodedState + "/webapp/" + appId), "",
                               requestHeaders);

        if (response.getResponseCode() == 200) {
            // if status != ok this will return an exception then test fail!
            VerificationUtil.checkAppStateChange(response);
            return response;
        } else {
            throw new Exception("Change state failed> " + response.getData());
        }
    }

    /**
     * add a new tag
     *
     * @param id      application id
     * @param tagName tag name
     * @return
     * @throws Exception
     */
    public HttpResponse addNewTag(String id, String tagName) throws Exception {
        checkAuthentication();
        requestHeaders.put("Content-Type", "application/json");
        HttpResponse response = HttpUtil.doPut(new URL(backEndUrl
                                                               + "/publisher/api/tag/webapp/" + id),
                                               "{\"tags\":[\" " + tagName + " \"]}",
                                               requestHeaders);

        if (response.getResponseCode() == 200) {
            //VerificationUtil.checkErrors(response);
            return response;
        } else {
            throw new Exception("Get Api Information failed> "
                                        + response.getData());
        }

    }

    public void setHttpHeader(String headerName, String value) {
        this.requestHeaders.put(headerName, value);
    }

    public String getHttpHeader(String headerName) {
        return this.requestHeaders.get(headerName);
    }

    public void removeHttpHeader(String headerName) {
        this.requestHeaders.remove(headerName);
    }

    private String setSession(String session) {
        return requestHeaders.put("Cookie", session);
    }

    /**
     * method to check whether user is logged in
     *
     * @return
     * @throws Exception
     */
    private boolean checkAuthentication() throws Exception {
        if (requestHeaders.get("Cookie") == null) {
            throw new Exception("No Session Cookie found. Please login first");
        }
        return true;
    }

    private String getSession(Map<String, String> responseHeaders) {
        return responseHeaders.get("Set-Cookie");
    }

}
