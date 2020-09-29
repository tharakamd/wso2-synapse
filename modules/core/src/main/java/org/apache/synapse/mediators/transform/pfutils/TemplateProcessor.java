package org.apache.synapse.mediators.transform.pfutils;

import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.OMException;
import org.apache.axiom.om.impl.builder.StAXBuilder;
import org.apache.axiom.om.impl.builder.StAXOMBuilder;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.MessageContext;
import org.apache.synapse.config.xml.SynapsePath;
import org.apache.synapse.mediators.transform.Argument;
import org.apache.synapse.mediators.transform.ArgumentDetails;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;


public abstract class TemplateProcessor {
    protected final static String JSON_TYPE = "json";
    protected final static String XML_TYPE = "xml";
    protected final static String TEXT_TYPE = "text";
    protected final static String STRING_TYPE = "str";
    protected final static String ESCAPE_DOUBLE_QUOTE_WITH_FIVE_BACK_SLASHES = "\\\\\"";
    protected final static String ESCAPE_DOUBLE_QUOTE_WITH_NINE_BACK_SLASHES = "\\\\\\\\\"";
    protected final static String ESCAPE_BACK_SLASH_WITH_SIXTEEN_BACK_SLASHES = "\\\\\\\\\\\\\\\\";
    protected final static String ESCAPE_DOLLAR_WITH_SIX_BACK_SLASHES = "\\\\\\$";
    protected final static String ESCAPE_DOLLAR_WITH_TEN_BACK_SLASHES = "\\\\\\\\\\$";
    protected final static String ESCAPE_BACKSPACE_WITH_EIGHT_BACK_SLASHES = "\\\\\\\\b";
    protected final static String ESCAPE_FORMFEED_WITH_EIGHT_BACK_SLASHES = "\\\\\\\\f";
    protected final static String ESCAPE_NEWLINE_WITH_EIGHT_BACK_SLASHES = "\\\\\\\\n";
    protected final static String ESCAPE_CRETURN_WITH_EIGHT_BACK_SLASHES = "\\\\\\\\r";
    protected final static String ESCAPE_TAB_WITH_EIGHT_BACK_SLASHES = "\\\\\\\\t";
    protected final XMLInputFactory inputFactory = XMLInputFactory.newInstance();
    private String mediaType = XML_TYPE;
    private boolean escapeXmlChars = false;
    private List<Argument> pathArgumentList = new ArrayList<Argument>();

    private static final Log log = LogFactory.getLog(TemplateProcessor.class);

    public abstract String processTemplate(String template, String mediaType, MessageContext synCtx);

    /**
     * Goes through SynapsePath argument list, evaluating each by calling stringValueOf and returns a HashMap String, String
     * array where each item will contain a hash map with key "evaluated expression" and value "SynapsePath type".
     * @param synCtx
     * @return
     */
    protected HashMap<String, ArgumentDetails>[] getArgValues(MessageContext synCtx) {
        HashMap<String, ArgumentDetails>[] argValues = new HashMap[pathArgumentList.size()];
        HashMap<String, ArgumentDetails> valueMap;
        String value = "";
        for (int i = 0; i < pathArgumentList.size(); ++i) {       /*ToDo use foreach*/
            Argument arg = pathArgumentList.get(i);
            ArgumentDetails details = new ArgumentDetails();
            if (arg.getValue() != null) {
                value = arg.getValue();
                details.setXml(isXML(value));
                if (!details.isXml()) {
                    value = escapeXMLEnvelope(synCtx, value);
                }
                value = Matcher.quoteReplacement(value);
            } else if (arg.getExpression() != null) {
                value = arg.getExpression().stringValueOf(synCtx);
                details.setLiteral(arg.isLiteral());
                if (value != null) {
                    // XML escape the result of an expression that produces a literal, if the target format
                    // of the payload is XML.
                    details.setXml(isXML(value));
                    if (!details.isXml() && XML_TYPE.equals(getType()) && !isJson(value.trim(), arg.getExpression())) {
                        value = escapeXMLEnvelope(synCtx, value);
                    }
                    value = Matcher.quoteReplacement(value);
                } else {
                    value = "";
                }
            } else {
                handleException("Unexpected arg type detected", synCtx);
            }
            //value = value.replace(String.valueOf((char) 160), " ").trim();
            valueMap = new HashMap<String, ArgumentDetails>();
            if (null != arg.getExpression()) {
                details.setPathType(arg.getExpression().getPathType());
                valueMap.put(value, details);
            } else {
                details.setPathType(SynapsePath.X_PATH);
                valueMap.put(value, details);
            }
            argValues[i] = valueMap;
        }
        return argValues;
    }

    /**
     * Helper function that takes a Map of String, ArgumentDetails where key contains the value of an evaluated SynapsePath
     * expression and value contains the type of SynapsePath + deepcheck status in use.
     *
     * It returns the type of conversion required (XML | JSON | String) based on the actual returned value and the path
     * type.
     *
     * @param entry
     * @return
     */
    protected String inferReplacementType(Map.Entry<String, ArgumentDetails> entry) {
        if (entry.getValue().isLiteral()){
            return STRING_TYPE;
        } else if (entry.getValue().getPathType().equals(SynapsePath.X_PATH)
                && entry.getValue().isXml()) {
            return XML_TYPE;
        } else if(entry.getValue().getPathType().equals(SynapsePath.X_PATH)
                && !entry.getValue().isXml()) {
            return STRING_TYPE;
        } else if(entry.getValue().getPathType().equals(SynapsePath.JSON_PATH)
                && isJson(entry.getKey())) {
            return JSON_TYPE;
        } else if(entry.getValue().getPathType().equals(SynapsePath.JSON_PATH)
                && !isJson((entry.getKey()))) {
            return STRING_TYPE;
        } else {
            return STRING_TYPE;
        }
    }

    /**
     * Helper function that returns true if value passed is of JSON type.
     * @param value
     * @return
     */
    protected boolean isJson(String value) {
        return !(value == null || value.trim().isEmpty()) && (value.trim().charAt(0) == '{' || value.trim().charAt(0) == '[');
    }

    /**
     * Helper function that returns true if value passed is of JSON type and expression is JSON.
     */
    private boolean isJson(String value, SynapsePath expression) {
        return !(value == null || value.trim().isEmpty()) && (value.trim().charAt(0) == '{'
                || value.trim().charAt(0) == '[') && expression.getPathType().equals(SynapsePath.JSON_PATH);
    }


    /**
     * Helper function that returns true if value passed is of XML Type.
     *
     * @param value
     * @return
     */
    protected boolean isXML(String value) {
        try {
            value = value.trim();
            if (!value.endsWith(">") || value.length() < 4) {
                return false;
            }
            // validate xml
            convertStringToOM(value);
            return true;
        } catch (XMLStreamException ignore) {
            // means not a xml
            return false;
        } catch (OMException ignore) {
            // means not a xml
            return false;
        }
    }

    /**
     * Converts String to OMElement
     *
     * @param value String value to convert
     * @return parsed OMElement
     */
    protected OMElement convertStringToOM(String value) throws XMLStreamException, OMException {
        javax.xml.stream.XMLStreamReader xmlReader = inputFactory.createXMLStreamReader(new StringReader(value));
        StAXBuilder builder = new StAXOMBuilder(xmlReader);
        return builder.getDocumentElement();
    }

    /**
     * Helper method to replace required char values with escape characters.
     *
     * @param replaceString
     * @return replacedString
     */
    protected String escapeSpecialChars(String replaceString) {
        return replaceString.replaceAll(Matcher.quoteReplacement("\\\\"), ESCAPE_BACK_SLASH_WITH_SIXTEEN_BACK_SLASHES)
                .replaceAll("\"", ESCAPE_DOUBLE_QUOTE_WITH_NINE_BACK_SLASHES)
                .replaceAll("\b", ESCAPE_BACKSPACE_WITH_EIGHT_BACK_SLASHES)
                .replaceAll("\f", ESCAPE_FORMFEED_WITH_EIGHT_BACK_SLASHES)
                .replaceAll("\n", ESCAPE_NEWLINE_WITH_EIGHT_BACK_SLASHES)
                .replaceAll("\r", ESCAPE_CRETURN_WITH_EIGHT_BACK_SLASHES)
                .replaceAll("\t", ESCAPE_TAB_WITH_EIGHT_BACK_SLASHES);
    }

    /**
     * Replace special characters of a JSON string.
     * @param jsonString JSON string.
     * @return
     */
    protected String escapeSpecialCharactersOfJson(String jsonString) {
        // This is to replace \" with \\" and \\$ with \$. Because for Matcher, $ sign is
        // a special character and for JSON " is a special character.
        //replacing other json special characters i.e \b, \f, \n \r, \t
        return jsonString.replaceAll(ESCAPE_DOUBLE_QUOTE_WITH_FIVE_BACK_SLASHES,
                ESCAPE_DOUBLE_QUOTE_WITH_NINE_BACK_SLASHES)
                .replaceAll(ESCAPE_DOLLAR_WITH_TEN_BACK_SLASHES, ESCAPE_DOLLAR_WITH_SIX_BACK_SLASHES)
                .replaceAll("\\\\b", ESCAPE_BACKSPACE_WITH_EIGHT_BACK_SLASHES)
                .replaceAll("\\\\f", ESCAPE_FORMFEED_WITH_EIGHT_BACK_SLASHES)
                .replaceAll("\\\\n", ESCAPE_NEWLINE_WITH_EIGHT_BACK_SLASHES)
                .replaceAll("\\\\r", ESCAPE_CRETURN_WITH_EIGHT_BACK_SLASHES)
                .replaceAll("\\\\t", ESCAPE_TAB_WITH_EIGHT_BACK_SLASHES);
    }

    /**
     * Replace special characters of a XML string.
     * @param xmlString XML string.
     * @return
     */
    protected String escapeSpecialCharactersOfXml(String xmlString) {
        return xmlString.replaceAll(ESCAPE_DOUBLE_QUOTE_WITH_FIVE_BACK_SLASHES,
                ESCAPE_DOUBLE_QUOTE_WITH_NINE_BACK_SLASHES)
                .replaceAll("\\$", ESCAPE_DOLLAR_WITH_SIX_BACK_SLASHES)
                .replaceAll("\\\\b", ESCAPE_BACKSPACE_WITH_EIGHT_BACK_SLASHES)
                .replaceAll("\\\\f", ESCAPE_FORMFEED_WITH_EIGHT_BACK_SLASHES)
                .replaceAll("\\\\n", ESCAPE_NEWLINE_WITH_EIGHT_BACK_SLASHES)
                .replaceAll("\\\\r", ESCAPE_CRETURN_WITH_EIGHT_BACK_SLASHES)
                .replaceAll("\\\\t", ESCAPE_TAB_WITH_EIGHT_BACK_SLASHES);
    }

    /**
     * Helper method to replace required char values with escape characters for XML.
     *
     * @param replaceString
     * @return replacedString
     */
    protected String escapeXMLSpecialChars(String replaceString) {
        return replaceString.replaceAll("\b", ESCAPE_BACKSPACE_WITH_EIGHT_BACK_SLASHES)
                .replaceAll("\f", ESCAPE_FORMFEED_WITH_EIGHT_BACK_SLASHES)
                .replaceAll("\n", ESCAPE_NEWLINE_WITH_EIGHT_BACK_SLASHES)
                .replaceAll("\r", ESCAPE_CRETURN_WITH_EIGHT_BACK_SLASHES)
                .replaceAll("\t", ESCAPE_TAB_WITH_EIGHT_BACK_SLASHES);
    }

    /**
     * Escapes XML special characters
     *
     * @param msgCtx Message Context
     * @param value XML String which needs to be escaped
     * @return XML special char escaped string
     */
    protected String escapeXMLEnvelope(MessageContext msgCtx, String value) {
        String xmlVersion = "1.0"; //Default is set to 1.0

        try {
            xmlVersion = checkXMLVersion(msgCtx);
        } catch (IOException e) {
            log.error("Error reading message envelope", e);
        } catch (SAXException e) {
            log.error("Error parsing message envelope", e);
        } catch (ParserConfigurationException e) {
            log.error("Error building message envelope document", e);
        }

        if("1.1".equals(xmlVersion)) {
            return org.apache.commons.text.StringEscapeUtils.escapeXml11(value);
        } else {
            return org.apache.commons.text.StringEscapeUtils.escapeXml10(value);
        }

    }

    /**
     * Checks and returns XML version of the envelope
     *
     * @param msgCtx Message Context
     * @return xmlVersion in XML Declaration
     * @throws ParserConfigurationException failure in building message envelope document
     * @throws IOException Error reading message envelope
     * @throws SAXException Error parsing message envelope
     */
    private String checkXMLVersion(MessageContext msgCtx) throws IOException, SAXException, ParserConfigurationException {
        DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();
        InputSource inputSource = new InputSource(new StringReader(msgCtx.getEnvelope().toString()));
        Document document = documentBuilder.parse(inputSource);
        return document.getXmlVersion();
    }

    protected boolean isEscapeXmlChars() {
        return escapeXmlChars;
    }

    public void setEscapeXmlChars(boolean escapeXmlChars) {
        this.escapeXmlChars = escapeXmlChars;
    }

    public String getType() {
        return mediaType;
    }

    public void setType(String type) {
        this.mediaType = type;
    }

    // todo :: replace with a exception based mechanism
    protected void handleException(String msg, MessageContext msgContext){

    }
}
