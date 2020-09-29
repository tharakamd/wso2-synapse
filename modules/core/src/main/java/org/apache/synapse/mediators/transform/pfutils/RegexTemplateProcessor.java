package org.apache.synapse.mediators.transform.pfutils;

import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.OMException;
import org.apache.axis2.AxisFault;
import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.MessageContext;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.mediators.transform.ArgumentDetails;

import javax.xml.stream.XMLStreamException;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.apache.synapse.mediators.transform.PayloadFactoryMediator.QUOTE_STRING_IN_PAYLOAD_FACTORY_JSON;

public class RegexTemplateProcessor extends TemplateProcessor {

    private final Pattern pattern = Pattern.compile("\\$(\\d)+");
    private static final Pattern validJsonNumber = Pattern.compile("^-?(0|([1-9]\\d*))(\\.\\d+)?([eE][+-]?\\d+)?$");

    private static final Log log = LogFactory.getLog(RegexTemplateProcessor.class);

    @Override
    public String processTemplate(String template, String mediaType, MessageContext synCtx) {

        StringBuffer result = new StringBuffer();
        replace(template, result, mediaType, synCtx);
        return result.toString();
    }

    /**
     * Replaces the payload format with SynapsePath arguments which are evaluated using getArgValues().
     *
     * @param format
     * @param result
     * @param synCtx
     */
    private void replace(String format, StringBuffer result, String mediaType, MessageContext synCtx) {

        HashMap<String, ArgumentDetails>[] argValues = getArgValues(mediaType,synCtx);
        HashMap<String, ArgumentDetails> replacement;
        Map.Entry<String, ArgumentDetails> replacementEntry;
        String replacementValue = null;
        Matcher matcher;

        if (JSON_TYPE.equals(mediaType) || TEXT_TYPE.equals(mediaType)) {
            matcher = pattern.matcher(format);
        } else {
            matcher = pattern.matcher("<pfPadding>" + format + "</pfPadding>");
        }
        try {
            while (matcher.find()) {
                String matchSeq = matcher.group();
                int argIndex;
                try {
                    argIndex = Integer.parseInt(matchSeq.substring(1, matchSeq.length()));
                } catch (NumberFormatException e) {
                    argIndex = Integer.parseInt(matchSeq.substring(2, matchSeq.length() - 1));
                }
                replacement = argValues[argIndex - 1];
                replacementEntry = replacement.entrySet().iterator().next();
                if (mediaType.equals(JSON_TYPE) && inferReplacementType(replacementEntry).equals(XML_TYPE)) {
                    // XML to JSON conversion here
                    try {
                        replacementValue = "<jsonObject>" + replacementEntry.getKey() + "</jsonObject>";
                        OMElement omXML = convertStringToOM(replacementValue);
                        replacementValue = JsonUtil.toJsonString(omXML).toString();
                        replacementValue = escapeSpecialCharactersOfJson(replacementValue);
                    } catch (XMLStreamException e) {
                        handleException(
                                "Error parsing XML for JSON conversion, please check your xPath expressions return valid XML: ",
                                synCtx);
                    } catch (AxisFault e) {
                        handleException("Error converting XML to JSON", synCtx);
                    } catch (OMException e) {
                        //if the logic comes to this means, it was tried as a XML, which means it has
                        // "<" as starting element and ">" as end element, so basically if the logic comes here, that means
                        //value is a string value, that means No conversion required, as path evaluates to regular String.
                        replacementValue = replacementEntry.getKey();

                        // This is to replace " with \" and \\ with \\\\
                        //replacing other json special characters i.e \b, \f, \n \r, \t
                        replacementValue = escapeSpecialChars(replacementValue);

                    }
                } else if (mediaType.equals(XML_TYPE) && inferReplacementType(replacementEntry).equals(JSON_TYPE)) {
                    // JSON to XML conversion here
                    try {
                        replacementValue = replacementEntry.getKey();
                        replacementValue = escapeSpecialCharactersOfXml(replacementValue);
                        OMElement omXML = JsonUtil.toXml(IOUtils.toInputStream(replacementValue), false);
                        if (JsonUtil.isAJsonPayloadElement(omXML)) { // remove <jsonObject/> from result.
                            Iterator children = omXML.getChildElements();
                            String childrenStr = "";
                            while (children.hasNext()) {
                                childrenStr += (children.next()).toString().trim();
                            }
                            replacementValue = childrenStr;
                        } else { ///~
                            replacementValue = omXML.toString();
                        }
                        //replacementValue = omXML.toString();
                    } catch (AxisFault e) {
                        handleException(
                                "Error converting JSON to XML, please check your JSON Path expressions return valid JSON: ",
                                synCtx);
                    }
                } else {
                    // No conversion required, as path evaluates to regular String.
                    replacementValue = replacementEntry.getKey();
                    String trimmedReplacementValue = replacementValue.trim();
                    //If media type is xml and replacement value is json convert it to xml format prior to replacement
                    if (mediaType.equals(XML_TYPE) && inferReplacementType(replacementEntry).equals(STRING_TYPE)
                            && isJson(trimmedReplacementValue)) {
                        try {
                            replacementValue = escapeSpecialCharactersOfXml(replacementValue);
                            OMElement omXML = JsonUtil.toXml(IOUtils.toInputStream(replacementValue), false);
                            if (JsonUtil.isAJsonPayloadElement(omXML)) { // remove <jsonObject/> from result.
                                Iterator children = omXML.getChildElements();
                                String childrenStr = "";
                                while (children.hasNext()) {
                                    childrenStr += (children.next()).toString().trim();
                                }
                                replacementValue = childrenStr;
                            } else {
                                replacementValue = omXML.toString();
                            }
                        } catch (AxisFault e) {
                            handleException("Error converting JSON to XML, please check your JSON Path expressions"
                                    + " return valid JSON: ", synCtx);
                        }
                    } else if (mediaType.equals(JSON_TYPE) &&
                            inferReplacementType(replacementEntry).equals(JSON_TYPE) &&
                            isEscapeXmlChars()) {
                        //checks whether the escapeXmlChars attribute is true when media-type and evaluator is json and
                        //escapes xml chars. otherwise json messages with non escaped xml characters will fail to build
                        //in content aware mediators.
                        replacementValue = escapeXMLSpecialChars(replacementValue);
                    } else if (mediaType.equals(JSON_TYPE) &&
                            inferReplacementType(replacementEntry).equals(STRING_TYPE) &&
                            (!trimmedReplacementValue.startsWith("{") && !trimmedReplacementValue.startsWith("["))) {
                        replacementValue = escapeSpecialChars(replacementValue);
                        // Check for following property which will force the string to include quotes
                        Object force_string_quote = synCtx.getProperty(QUOTE_STRING_IN_PAYLOAD_FACTORY_JSON);
                        // skip double quotes if replacement is boolean or null or valid json number
                        if (force_string_quote != null && ((String) force_string_quote).equalsIgnoreCase("true")
                                && !trimmedReplacementValue.equals("true") && !trimmedReplacementValue.equals("false")
                                && !trimmedReplacementValue.equals("null")
                                && !validJsonNumber.matcher(trimmedReplacementValue).matches()) {
                            replacementValue = "\"" + replacementValue + "\"";
                        }
                    } else if (
                            (mediaType.equals(JSON_TYPE) && inferReplacementType(replacementEntry).equals(JSON_TYPE)) &&
                                    (!trimmedReplacementValue.startsWith("{") &&
                                            !trimmedReplacementValue.startsWith("["))) {
                        // This is to handle only the string value
                        replacementValue =
                                replacementValue.replaceAll("\"", ESCAPE_DOUBLE_QUOTE_WITH_NINE_BACK_SLASHES);
                    }
                }
                matcher.appendReplacement(result, replacementValue);
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            log.error("#replace. Mis-match detected between number of formatters and arguments", e);
        }
        matcher.appendTail(result);
    }
}
