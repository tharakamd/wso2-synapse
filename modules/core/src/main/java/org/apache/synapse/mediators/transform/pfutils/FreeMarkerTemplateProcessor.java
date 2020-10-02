package org.apache.synapse.mediators.transform.pfutils;

import com.google.gson.Gson;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import org.apache.synapse.MessageContext;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.util.PayloadHelper;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.HashMap;
import java.util.Map;

import javax.xml.parsers.ParserConfigurationException;

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
            Template freeMarkerTemplate = new Template("synapse-template", templateString, cfg);
            Map<String, Object> data = new HashMap<>();
            int payloadType = getPayloadType(messageContext);
            injectPayloadVariables(messageContext, data, payloadType);
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

    private void injectPayloadVariables(MessageContext messageContext, Map<String, Object> data, int payloadType)
            throws SAXException, IOException, ParserConfigurationException {

        if (payloadType == XML_PAYLOAD_TYPE) {
            data.put(PAYLOAD_INJECTING_NAME, freemarker.ext.dom.NodeModel.parse(
                    new InputSource(new StringReader(
                            messageContext.getEnvelope().getBody().getFirstElement().toString()))));
        } else if (payloadType == JSON_PAYLOAD_TYPE) {
            String jsonPayloadString =
                    JsonUtil.jsonPayloadToString(((Axis2MessageContext) messageContext).getAxis2MessageContext());
            Map<String, Object> map = new HashMap<>();
            map = (Map<String, Object>) gson.fromJson(jsonPayloadString, map.getClass());
            data.put(PAYLOAD_INJECTING_NAME, map);
        }
    }

    private int getPayloadType(MessageContext messageContext) {

        if (PayloadHelper.getPayloadType(messageContext) == PayloadHelper.XMLPAYLOADTYPE) {
            if (JsonUtil.hasAJsonPayload(((Axis2MessageContext) messageContext).getAxis2MessageContext())) {
                return JSON_PAYLOAD_TYPE;
            }else{
                return XML_PAYLOAD_TYPE;
            }
        } else {
            handleException("Invalid payload type. Supports only XML and JSON payloads");
        }

        return NOT_SUPPORTING_PAYLOAD_TYPE;
    }
}
