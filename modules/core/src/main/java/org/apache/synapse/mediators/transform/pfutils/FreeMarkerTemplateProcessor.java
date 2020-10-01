package org.apache.synapse.mediators.transform.pfutils;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import org.apache.synapse.MessageContext;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.HashMap;
import java.util.Map;

public class FreeMarkerTemplateProcessor extends TemplateProcessor {

    private final Configuration cfg;

    public FreeMarkerTemplateProcessor() {

        cfg = new Configuration();
    }

    @Override
    public String processTemplate(String templateString, String mediaType, MessageContext synCtx) {

        try {
            Template freeMarkerTemplate = new Template("synapse-template", templateString, cfg);
            Map<String, Object> data = new HashMap<>();
            data.put("name", "vanila");
            Writer out = new StringWriter();
            freeMarkerTemplate.process(data, out);
            return out.toString();
        } catch (IOException | TemplateException e) {
            handleException("Error parsing FreeMarking template");
        }

        return "";
    }
}
