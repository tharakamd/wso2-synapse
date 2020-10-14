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
import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.OMNode;
import org.apache.axiom.om.OMText;
import org.apache.commons.text.StringEscapeUtils;
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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.ParserConfigurationException;

import static org.apache.synapse.mediators.transform.pfutils.Constants.ARGS_INJECTING_NAME;
import static org.apache.synapse.mediators.transform.pfutils.Constants.ARGS_INJECTING_PREFIX;
import static org.apache.synapse.mediators.transform.pfutils.Constants.AXIS2_PROPERTY_INJECTING_NAME;
import static org.apache.synapse.mediators.transform.pfutils.Constants.CTX_PROPERTY_INJECTING_NAME;
import static org.apache.synapse.mediators.transform.pfutils.Constants.JSON_PAYLOAD_TYPE;
import static org.apache.synapse.mediators.transform.pfutils.Constants.NOT_SUPPORTING_PAYLOAD_TYPE;
import static org.apache.synapse.mediators.transform.pfutils.Constants.PAYLOAD_INJECTING_NAME;
import static org.apache.synapse.mediators.transform.pfutils.Constants.TEXT_PAYLOAD_TYPE;
import static org.apache.synapse.mediators.transform.pfutils.Constants.TRANSPORT_PROPERTY_INJECTING_NAME;
import static org.apache.synapse.mediators.transform.pfutils.Constants.XML_PAYLOAD_TYPE;
import static org.apache.synapse.util.PayloadHelper.TEXTELT;
import static org.apache.synapse.util.PayloadHelper.getXMLPayload;

public class FreeMarkerTemplateProcessor extends TemplateProcessor {

    private final Configuration cfg;
    private final Gson gson;
    private Template freeMarkerTemplate;

    public FreeMarkerTemplateProcessor() {

        cfg = new Configuration(Configuration.VERSION_2_3_30);
        gson = new Gson();
    }

    @Override
    public void executePreProcessing() {

        compileFreeMarkerTemplate(getFormat(), getMediaType());
    }

    @Override
    public String processTemplate(String templateString, String mediaType, MessageContext messageContext,
                                  boolean isFormatDynamic) {

        try {
            if (isFormatDynamic) {
                compileFreeMarkerTemplate(templateString, mediaType);
            }

            Map<String, Object> data = new HashMap<>();
            int payloadType = getPayloadType(messageContext);
            injectPayloadVariables(messageContext, payloadType, data);
            injectArgs(messageContext, mediaType, data);
            injectProperties(messageContext, data);

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

    private void compileFreeMarkerTemplate(String templateString, String mediaType) {

        try {
            if (XML_TYPE.equals(mediaType)) {
                templateString = "<pfPadding>" + templateString + "</pfPadding>";
            }

            freeMarkerTemplate = new Template("synapse-template", templateString, cfg);
        } catch (IOException e) {
            handleException("Error parsing FreeMarking template");
        }
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
        } else if (payloadType == TEXT_PAYLOAD_TYPE) {
            injectTextPayload(messageContext, data);
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
     * Inject an Text payload in to FreeMarker
     *
     * @param messageContext Message context
     * @param data           FreeMarker data input
     */
    private void injectTextPayload(MessageContext messageContext, Map<String, Object> data) {

        String textPayload;
        OMElement el = getXMLPayload(messageContext.getEnvelope());
        if (el == null || !el.getQName().equals(TEXTELT)) {
            textPayload = "";
        } else {
            textPayload = getTextValue(el);
        }

        data.put(PAYLOAD_INJECTING_NAME, textPayload);
    }

    /**
     * Get text value from OMNode
     *
     * @param node OMNode to get text value
     * @return text value of the node
     */
    private String getTextValue(OMNode node) {

        switch (node.getType()) {
            case OMNode.ELEMENT_NODE:
                StringBuilder sb = new StringBuilder();
                Iterator<OMNode> children = ((OMElement) node).getChildren();
                while (children.hasNext()) {
                    sb.append(getTextValue(children.next()));
                }
                return sb.toString();
            case OMNode.TEXT_NODE:
                String text = ((OMText) node).getText();
                return StringEscapeUtils.escapeXml11(text);
            default:
                return "";
        }
    }

    private void injectProperties(MessageContext synCtx, Map<String, Object> data) {

        injectCtxProperties(synCtx, data);
        injectAxis2Properties(synCtx, data);
        injectTransportProperties(synCtx, data);
    }

    private void injectCtxProperties(MessageContext synCtx, Map<String, Object> data) {

        Map<String, String> properties = new HashMap<>();
        Set propertyKeys = synCtx.getPropertyKeySet();
        for (Object propertyKey : propertyKeys) {
            String propertyKeyString = propertyKey.toString();
            Object propertyValue = synCtx.getProperty(propertyKeyString);
            if (propertyValue != null) {
                properties.put(propertyKeyString, propertyValue.toString());
            }
        }
        data.put(CTX_PROPERTY_INJECTING_NAME, properties);
    }

    private void injectAxis2Properties(MessageContext synCtx, Map<String, Object> data) {

        Map<String, String> properties = new HashMap<>();
        org.apache.axis2.context.MessageContext axis2MessageContext
                = ((Axis2MessageContext) synCtx).getAxis2MessageContext();
        Iterator<String> propertyNames = axis2MessageContext.getPropertyNames();
        while (propertyNames.hasNext()) {
            String propertyName = propertyNames.next();
            Object propertyValue = axis2MessageContext.getProperty(propertyName);
            if (propertyValue != null) {
                properties.put(propertyName, propertyValue.toString());
            }
        }
        data.put(AXIS2_PROPERTY_INJECTING_NAME, properties);
    }

    private void injectTransportProperties(MessageContext synCtx, Map<String, Object> data) {

        Map<String, String> properties = new HashMap<>();
        org.apache.axis2.context.MessageContext axis2MessageContext
                = ((Axis2MessageContext) synCtx).getAxis2MessageContext();
        Object headers = axis2MessageContext.getProperty(
                org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS);
        if (headers instanceof Map) {
            Map headersMap = (Map) headers;
            for (Object propertyKey : headersMap.keySet()) {
                Object propertyValue = headersMap.get(propertyKey);
                if (propertyValue != null) {
                    properties.put(propertyKey.toString(), propertyValue.toString());
                }
            }
        }
        data.put(TRANSPORT_PROPERTY_INJECTING_NAME, properties);
    }

    /**
     * Get the input payload type
     *
     * @param messageContext Message context
     * @return Type of the input paylaod
     */
    private int getPayloadType(MessageContext messageContext) {

        int payloadType = PayloadHelper.getPayloadType(messageContext);
        if (payloadType == PayloadHelper.XMLPAYLOADTYPE) {
            if (JsonUtil.hasAJsonPayload(((Axis2MessageContext) messageContext).getAxis2MessageContext())) {
                return JSON_PAYLOAD_TYPE;
            } else if (PayloadHelper.getTextPayload(messageContext) != null) {
                return TEXT_PAYLOAD_TYPE;
            } else {
                return XML_PAYLOAD_TYPE;
            }
        } else if (payloadType == PayloadHelper.TEXTPAYLOADTYPE) {
            return TEXT_PAYLOAD_TYPE;
        } else {
            handleException("Invalid payload type. Supports only XML, JSON and TEXT payloads");
        }

        return NOT_SUPPORTING_PAYLOAD_TYPE;
    }

    private boolean isTextPayload(MessageContext mc) {

        return PayloadHelper.getTextPayload(mc) != null;
    }
}