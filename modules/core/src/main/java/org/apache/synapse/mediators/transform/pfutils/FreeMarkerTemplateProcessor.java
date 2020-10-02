/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *   * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.apache.synapse.mediators.transform.pfutils;

import com.google.gson.Gson;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import org.apache.synapse.MessageContext;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.mediators.transform.ArgumentDetails;
import org.apache.synapse.util.PayloadHelper;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.ParserConfigurationException;

import static org.apache.synapse.mediators.transform.pfutils.Constants.ARGS_INJECTING_NAME;
import static org.apache.synapse.mediators.transform.pfutils.Constants.ARGS_INJECTING_PREFIX;
import static org.apache.synapse.mediators.transform.pfutils.Constants.JSON_PAYLOAD_TYPE;
import static org.apache.synapse.mediators.transform.pfutils.Constants.NOT_SUPPORTING_PAYLOAD_TYPE;
import static org.apache.synapse.mediators.transform.pfutils.Constants.PAYLOAD_INJECTING_NAME;
import static org.apache.synapse.mediators.transform.pfutils.Constants.XML_PAYLOAD_TYPE;

public class FreeMarkerTemplateProcessor extends TemplateProcessor {

    private final Configuration cfg;
    private final Gson gson;

    public FreeMarkerTemplateProcessor() {

        cfg = new Configuration();
        gson = new Gson();
    }

    @Override
    public String processTemplate(String templateString, String mediaType, MessageContext messageContext) {

        try {
            if (XML_TYPE.equals(mediaType)) {
                templateString = "<pfPadding>" + templateString + "</pfPadding>";
            }
            Template freeMarkerTemplate = new Template("synapse-template", templateString, cfg);
            Map<String, Object> data = new HashMap<>();
            int payloadType = getPayloadType(messageContext);
            injectPayloadVariables(messageContext, payloadType, data);
            injectArgs(messageContext, mediaType, data);
            Writer out = new StringWriter();
            freeMarkerTemplate.process(data, out);
            return out.toString();
        } catch (IOException | TemplateException e) {
            handleException("Error parsing FreeMarking template");
        } catch (SAXException | ParserConfigurationException e) {
            handleException("Error reading payload data");
        }

        return "";
    }

    /**
     * Inject argument values to freemarker
     *
     * @param messageContext Message context
     * @param mediaType      Output media type
     * @param data           Freemarker data input
     */
    private void injectArgs(MessageContext messageContext, String mediaType, Map<String, Object> data) {

        HashMap<String, Object> argsValues = new HashMap<>();
        HashMap<String, ArgumentDetails>[] argValues = getArgValues(mediaType, messageContext);
        for (int i = 0; i < argValues.length; i++) {
            HashMap<String, ArgumentDetails> argValue = argValues[i];
            Map.Entry<String, ArgumentDetails> argumentDetailsEntry = argValue.entrySet().iterator().next();
            String replacementValue = prepareReplacementValue(mediaType, messageContext, argumentDetailsEntry);
            argsValues.put(ARGS_INJECTING_PREFIX + (i + 1), replacementValue);
        }
        data.put(ARGS_INJECTING_NAME, argsValues);
    }

    /**
     * Inject  payload in to FreeMarker
     *
     * @param messageContext MessageContext
     * @param payloadType    Input payload type
     * @param data           FreeMarker data input
     * @throws SAXException
     * @throws IOException
     * @throws ParserConfigurationException
     */
    private void injectPayloadVariables(MessageContext messageContext, int payloadType, Map<String, Object> data)
            throws SAXException, IOException, ParserConfigurationException {

        if (payloadType == XML_PAYLOAD_TYPE) {
            injectXmlPayload(messageContext, data);
        } else if (payloadType == JSON_PAYLOAD_TYPE) {
            injectJsonPayload((Axis2MessageContext) messageContext, data);
        }
    }

    /**
     * Inject a JSON payload in the FreeMarker
     *
     * @param messageContext Message context
     * @param data           FreeMarker data input
     */
    private void injectJsonPayload(Axis2MessageContext messageContext, Map<String, Object> data) {

        org.apache.axis2.context.MessageContext axis2MessageContext = messageContext.getAxis2MessageContext();
        String jsonPayloadString = JsonUtil.jsonPayloadToString(axis2MessageContext);
        if (JsonUtil.hasAJsonObject(axis2MessageContext)) {
            injectJsonObject(data, jsonPayloadString);
        } else {
            injectJsonArray(data, jsonPayloadString);
        }
    }

    /**
     * Inject a JSON array in to FreeMarker
     *
     * @param data              FreeMarker data input
     * @param jsonPayloadString JSON payload string
     */
    private void injectJsonArray(Map<String, Object> data, String jsonPayloadString) {

        List<Object> array = new ArrayList<>();
        array = gson.fromJson(jsonPayloadString, array.getClass());
        data.put(PAYLOAD_INJECTING_NAME, array);
    }

    /**
     * Inject a JSON object in to FreeMarker
     *
     * @param data              FreeMarker data input
     * @param jsonPayloadString JSON payload string
     */
    private void injectJsonObject(Map<String, Object> data, String jsonPayloadString) {

        Map<String, Object> map = new HashMap<>();
        map = (Map<String, Object>) gson.fromJson(jsonPayloadString, map.getClass());
        data.put(PAYLOAD_INJECTING_NAME, map);
    }

    /**
     * Inject an XML payload in to FreeMarker
     *
     * @param messageContext Message context
     * @param data           FreeMarker data input
     * @throws SAXException
     * @throws IOException
     * @throws ParserConfigurationException
     */
    private void injectXmlPayload(MessageContext messageContext, Map<String, Object> data)
            throws SAXException, IOException, ParserConfigurationException {

        data.put(PAYLOAD_INJECTING_NAME, freemarker.ext.dom.NodeModel.parse(
                new InputSource(new StringReader(
                        messageContext.getEnvelope().getBody().getFirstElement().toString()))));
    }

    /**
     * Get the input payload type
     *
     * @param messageContext Message context
     * @return Type of the input paylaod
     */
    private int getPayloadType(MessageContext messageContext) {

        if (PayloadHelper.getPayloadType(messageContext) == PayloadHelper.XMLPAYLOADTYPE) {
            if (JsonUtil.hasAJsonPayload(((Axis2MessageContext) messageContext).getAxis2MessageContext())) {
                return JSON_PAYLOAD_TYPE;
            } else {
                return XML_PAYLOAD_TYPE;
            }
        } else {
            handleException("Invalid payload type. Supports only XML and JSON payloads");
        }

        return NOT_SUPPORTING_PAYLOAD_TYPE;
    }
}
