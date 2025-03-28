/*
 *  Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.synapse.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import org.apache.axiom.om.OMException;
import org.apache.axiom.om.util.AXIOMUtil;
import org.apache.commons.lang.StringUtils;
import org.apache.synapse.MessageContext;
import org.apache.synapse.SynapseException;
import org.apache.synapse.config.xml.SynapsePath;
import org.apache.synapse.util.xpath.SynapseExpression;
import org.apache.synapse.util.xpath.SynapseExpressionUtils;
import org.apache.synapse.util.xpath.SynapseJsonPath;
import org.apache.synapse.util.xpath.SynapseXPath;
import org.jaxen.JaxenException;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.stream.XMLStreamException;

/**
 * Utils used to resolve expressions provided in inline texts in mediators
 *
 */
public final class InlineExpressionUtil {

    private static final String EXPRESSION_JSON_EVAL = "json-eval(";

    // Regex to identify expressions in inline text
    private static final Pattern EXPRESSION_PATTERN = Pattern.compile("(\\{[^\\s\",<>}\\]]+})");

    // Regex to identify synapse expressions ${expression} in inline text
    private static final Pattern SYNAPSE_EXPRESSION_PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{(.+?)}");

    private InlineExpressionUtil() {

    }

    /**
     * Checks whether inline text contains expressions
     * Inline expressions will be denoted inside {} without any whitespaces
     * e.g.: {$ctx:vendor}, {get-property('vendor')}
     *
     * @param inlineText Inline text string
     * @return true if the string contains inline expressions, false otherwise
     */
    public static boolean checkForInlineExpressions(String inlineText) {

        Matcher matcher = EXPRESSION_PATTERN.matcher(inlineText);
        return matcher.find();
    }

    /**
     * Replaces Dynamic Values represented by expressions in inline text
     *
     * @param messageContext Message Context
     * @param inlineText     Inline Text String containing the expressions
     * @return Inline text string with replaced dynamic values
     */
    public static String replaceDynamicValues(MessageContext messageContext, String inlineText) {
        inlineText = replaceValue(messageContext, inlineText, true);
        return inlineText;
    }

    /**
     * Common function to replace dynamic values in inline texts and expressions.
     *
     * @param messageContext Message Context.
     * @param text           Text value containing the expressions.
     * @param isInline       inline / expression.
     * @return Text value with replaced expressions.
     */
    private static String replaceValue(MessageContext messageContext, String text, boolean isInline) {

        Matcher matcher = EXPRESSION_PATTERN.matcher(text);
        while (matcher.find()) {
            String matchSeq = matcher.group();
            String surroundedString;
            try {
                surroundedString = text.substring(text.indexOf(matchSeq) - 1, text.indexOf(matchSeq) + matchSeq
                        .length() + 1);
            } catch (IndexOutOfBoundsException e) {
                // catch index out of bound exception when the expression is at the beginning or end of the text
                surroundedString = StringUtils.EMPTY;
            }
            String value = getDynamicValue(messageContext, matchSeq.substring(1, matchSeq.length() - 1));
            if (value == null) {
                value = StringUtils.EMPTY;
            }
            // If the string is neither XML or JSON, it is considered a String and must be wrapped in double quotes
            // If it is an empty string returned from a json-eval expression it must be wrapped in double quotes
            if (isInline && (
                    (value.isEmpty() && matchSeq.contains(EXPRESSION_JSON_EVAL)) ||
                    (!isValidXML(value) && !isValidJson(value) && (value.isEmpty() || !isSurroundedByQuotes(surroundedString)))
            )) {
                value = "\"" + value + "\"";
            }
            text = text.replace(matchSeq, value);
        }
        return text;
    }

    private static boolean isSurroundedByQuotes(String text) {

        return text.startsWith("\"") && text.endsWith("\"");
    }
    /**
     * Replaces Dynamic Values represented by expressions inside json-eval.
     *
     * @param messageContext Message Context.
     * @param inlineText     JSON eval text containing the expressions.
     * @return Inline text string with replaced dynamic values.
     */
    public static String replaceDynamicValuesForJSONPath(MessageContext messageContext, String inlineText) {
        inlineText = replaceValue(messageContext, inlineText, false);
        if (inlineText.startsWith("json-eval(")) {
            inlineText = inlineText.substring(10, inlineText.length() - 1);
        }
        return inlineText;
    }

    /**
     * Get dynamic value for the expression
     *
     * @param messageContext Message Context
     * @param expression     Expression to be resolved
     * @return value of the expression
     */
    public static String getDynamicValue(MessageContext messageContext, String expression) {

        SynapsePath path;
        try {
            if (expression.startsWith(EXPRESSION_JSON_EVAL)) {
                path = new SynapseJsonPath(expression.substring(10, expression.length() - 1));
            } else {
                path = new SynapseXPath(expression);
            }
        } catch (JaxenException e) {
            throw new SynapseException("Invalid expression for inline source format.");
        }
        return path.stringValueOf(messageContext);
    }

    /**
     * Parse string and identify whether it is a valid JSON string or not
     *
     * @param stringToValidate String to be validated
     * @return true if the JSON is valid, false if not
     */
    private static boolean isValidJson(String stringToValidate) {

        JsonParser parser = new JsonParser();
        boolean isValidJson = true;
        try {
            JsonElement element = parser.parse(stringToValidate);
            if (!(element instanceof JsonObject || element instanceof JsonArray ||
                    element instanceof JsonPrimitive || "null".equalsIgnoreCase(stringToValidate))) {
                isValidJson = false;
            }
        } catch (JsonSyntaxException ex) {
            // cannot parse with JSON. Going ahead with XML
            isValidJson = false;
        }
        return isValidJson;
    }

    /**
     * Parse string and identify whether it is a valid XML string or not
     *
     * @param stringToValidate String to be validated
     * @return true if the XML is valid, false if not
     */
    private static boolean isValidXML(String stringToValidate) {

        try {
            AXIOMUtil.stringToOM(stringToValidate);
            return true;
        } catch (XMLStreamException | OMException e) {
            // ignore
        }
        return false;
    }

    /**
     * Initialize the inline synapse expressions in the inline text and return whether the inline text is content aware
     * Inline expressions will be denoted inside ${}
     * e.g.: ${var.var1}, ${payload.element.id}
     *
     * @param inlineText      Inline text string
     * @param expressionCache Cache to store the synapse expressions
     * @return true if the string contains content aware inline synapse expressions, false otherwise
     */
    public static boolean initInlineSynapseExpressions(String inlineText, Map<String, SynapseExpression> expressionCache)
            throws JaxenException {

        boolean isContentAware = false;
        Matcher matcher = SYNAPSE_EXPRESSION_PLACEHOLDER_PATTERN.matcher(inlineText);
        while (matcher.find()) {
            // Extract the expression inside ${...} and add it to the cache
            String placeholder = matcher.group(1);
            SynapseExpression expression = new SynapseExpression(placeholder);
            if (expression.isContentAware()) {
                isContentAware = true;
            }
            expressionCache.put(placeholder, expression);
        }
        return isContentAware;
    }

    /**
     * Process the inline template and replace the synapse expressions with the resolved values
     *
     * @param synCtx          Message Context
     * @param template        Inline template
     * @param expressionCache Cache to store the synapse expressions
     * @return Processed inline template
     */
    public static String processInLineSynapseExpressionTemplate(MessageContext synCtx, String template,
                                                                Map<String, SynapseExpression> expressionCache)
            throws JaxenException {

        Matcher matcher = SYNAPSE_EXPRESSION_PLACEHOLDER_PATTERN.matcher(template);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            // Extract the expression inside ${...}
            String placeholder = matcher.group(1);
            SynapseExpression expression;
            if (expressionCache != null) {
                expression = expressionCache.get(placeholder);
                if (expression == null) {
                    expression = new SynapseExpression(placeholder);
                    expressionCache.put(placeholder, expression);
                }
            } else {
                expression = new SynapseExpression(placeholder);
            }
            String replacement = expression.stringValueOf(synCtx);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Process the inline template and replace the synapse expressions with the resolved values
     *d
     * @param synCtx          Message Context
     * @param template        Inline template
     * @return Processed inline template
     */
    public static String processInLineSynapseExpressionTemplate(MessageContext synCtx, String template)
            throws JaxenException {

        return processInLineSynapseExpressionTemplate(synCtx, template, null);
    }
}
