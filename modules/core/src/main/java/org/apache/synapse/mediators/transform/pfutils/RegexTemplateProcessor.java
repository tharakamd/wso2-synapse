package org.apache.synapse.mediators.transform.pfutils;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.MessageContext;
import org.apache.synapse.mediators.transform.ArgumentDetails;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RegexTemplateProcessor extends TemplateProcessor {

    private final Pattern pattern = Pattern.compile("\\$(\\d)+");
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

        HashMap<String, ArgumentDetails>[] argValues = getArgValues(mediaType, synCtx);
        HashMap<String, ArgumentDetails> replacement;
        Map.Entry<String, ArgumentDetails> replacementEntry;
        String replacementValue;
        Matcher matcher;

        if (JSON_TYPE.equals(mediaType) || TEXT_TYPE.equals(mediaType)) {
            matcher = pattern.matcher(format);
        } else {
            matcher = pattern.matcher("<pfPadding>" + format + "</pfPadding>");
        }
        try {
            while (matcher.find()) {
                String matchSeq = matcher.group();
                replacement = getReplacementValue(argValues, matchSeq);
                replacementEntry = replacement.entrySet().iterator().next();
                replacementValue = prepareReplacementValue(mediaType, synCtx, replacementEntry);
                matcher.appendReplacement(result, replacementValue);
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            log.error("#replace. Mis-match detected between number of formatters and arguments", e);
        }
        matcher.appendTail(result);
    }

    private HashMap<String, ArgumentDetails> getReplacementValue(HashMap<String, ArgumentDetails>[] argValues,
                                                                 String matchSeq) {

        HashMap<String, ArgumentDetails> replacement;
        int argIndex;
        try {
            argIndex = Integer.parseInt(matchSeq.substring(1));
        } catch (NumberFormatException e) {
            argIndex = Integer.parseInt(matchSeq.substring(2, matchSeq.length() - 1));
        }
        replacement = argValues[argIndex - 1];
        return replacement;
    }
}
