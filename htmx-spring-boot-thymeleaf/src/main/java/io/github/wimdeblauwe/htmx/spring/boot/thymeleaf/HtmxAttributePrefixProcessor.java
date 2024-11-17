package io.github.wimdeblauwe.htmx.spring.boot.thymeleaf;

import org.attoparser.util.TextUtil;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.engine.AttributeName;
import org.thymeleaf.exceptions.TemplateProcessingException;
import org.thymeleaf.model.IAttribute;
import org.thymeleaf.model.IProcessableElementTag;
import org.thymeleaf.processor.AbstractProcessor;
import org.thymeleaf.processor.element.IElementTagProcessor;
import org.thymeleaf.processor.element.IElementTagStructureHandler;
import org.thymeleaf.processor.element.MatchingAttributeName;
import org.thymeleaf.processor.element.MatchingElementName;
import org.thymeleaf.standard.expression.*;
import org.thymeleaf.standard.processor.StandardDefaultAttributesTagProcessor;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.util.EscapedAttributeUtils;

public class HtmxAttributePrefixProcessor extends AbstractProcessor implements IElementTagProcessor {
    // Setting to Integer.MAX_VALUE is alright - we will always be limited by the dialect precedence anyway
    private static final int PRECEDENCE = Integer.MAX_VALUE;
    private static final TemplateMode TEMPLATE_MODE = TemplateMode.HTML;

    private final String dialectPrefix;
    private final MatchingAttributeName matchingAttributeName;
    private final String attrName;

    public HtmxAttributePrefixProcessor(final String dialectPrefix,
                                        String attrName) {
        super(TEMPLATE_MODE, PRECEDENCE);
        this.dialectPrefix = dialectPrefix;
        this.matchingAttributeName = MatchingAttributeName.forAllAttributesWithPrefix(getTemplateMode(), dialectPrefix);
        this.attrName = attrName;
    }


    public final MatchingElementName getMatchingElementName() {
        return null;
    }


    public final MatchingAttributeName getMatchingAttributeName() {
        return this.matchingAttributeName;
    }

    @Override
    public void process(ITemplateContext context,
                        IProcessableElementTag tag,
                        IElementTagStructureHandler structureHandler) {
        final TemplateMode templateMode = getTemplateMode();
        final IAttribute[] attributes = tag.getAllAttributes();

        // Should be no problem in performing modifications during iteration, as the attributeNames list
        // should not be affected by modifications on the original tag attribute set
        for (final IAttribute attribute : attributes) {

            final AttributeName attributeName = attribute.getAttributeDefinition().getAttributeName();
            if (attributeName.isPrefixed()) {
                if (TextUtil.equals(templateMode.isCaseSensitive(), attributeName.getPrefix(), this.dialectPrefix)
                        && attribute.getAttributeDefinition().getAttributeName().getAttributeName().startsWith(attrName)) {

                    // We will process each 'default' attribute separately
                    processDefaultAttribute(getTemplateMode(), context, tag, attribute, structureHandler);

                }
            }

        }

    }

    private static void processDefaultAttribute(
            final TemplateMode templateMode,
            final ITemplateContext context,
            final IProcessableElementTag tag, final IAttribute attribute,
            final IElementTagStructureHandler structureHandler) {

        try {

            final AttributeName attributeName = attribute.getAttributeDefinition().getAttributeName();
            final String attributeValue =
                    EscapedAttributeUtils.unescapeAttribute(context.getTemplateMode(), attribute.getValue());


            /*
             * Compute the new attribute name (i.e. the same, without the prefix)
             */
            final String originalCompleteAttributeName = attribute.getAttributeCompleteName();
            final String canonicalAttributeName = attributeName.getAttributeName();

            final String newAttributeName;
            if (TextUtil.endsWith(true, originalCompleteAttributeName, canonicalAttributeName)) {
                newAttributeName = canonicalAttributeName; // We avoid creating a new String instance
            } else {
                newAttributeName =
                        originalCompleteAttributeName.substring(originalCompleteAttributeName.length() - canonicalAttributeName.length());
            }


            /*
             * Obtain the parser
             */
            final IStandardExpressionParser expressionParser = StandardExpressions.getExpressionParser(context.getConfiguration());

            /*
             * Execute the expression, handling nulls in a way consistent with the rest of the Standard Dialect
             */
            final Object expressionResult;
            if (attributeValue != null) {

                final IStandardExpression expression = expressionParser.parseExpression(context, attributeValue);

                if (expression != null && expression instanceof FragmentExpression) {
                    // This is merely a FragmentExpression (not complex, not combined with anything), so we can apply a shortcut
                    // so that we don't require a "null" result for this expression if the template does not exist. That will
                    // save a call to resource.exists() which might be costly.

                    final FragmentExpression.ExecutedFragmentExpression executedFragmentExpression =
                            FragmentExpression.createExecutedFragmentExpression(context, (FragmentExpression) expression);

                    expressionResult =
                            FragmentExpression.resolveExecutedFragmentExpression(context, executedFragmentExpression, true);

                } else {

                    // Default attributes will ALWAYS be executed in RESTRICTED mode, for safety reasons (they might
                    // create attributes involved in code execution)
                    expressionResult = expression.execute(context, StandardExpressionExecutionContext.RESTRICTED);

                }

            } else {
                expressionResult = null;
            }

            /*
             * If the result of this expression is NO-OP, there is nothing to execute
             */
            if (expressionResult == NoOpToken.VALUE) {
                structureHandler.removeAttribute(attributeName);
                return;
            }

            /*
             * Compute the new attribute value
             */
            final String newAttributeValue =
                    EscapedAttributeUtils.escapeAttribute(templateMode, expressionResult == null ? null : expressionResult.toString());

            /*
             * Set the new value, removing the attribute completely if the expression evaluated to null
             */
            if (newAttributeValue == null || newAttributeValue.length() == 0) {
                // We are removing the equivalent attribute name, without the prefix...
                structureHandler.removeAttribute(newAttributeName);
                structureHandler.removeAttribute(attributeName);
            } else {
                // We are setting the equivalent attribute name, with the hx- prefix
                structureHandler.replaceAttribute(attributeName, "hx-" + newAttributeName, (newAttributeValue == null ? "" : newAttributeValue));
            }

        } catch (final TemplateProcessingException e) {
            // This is a nice moment to check whether the execution raised an error and, if so, add location information
            // Note this is similar to what is done at the superclass AbstractElementTagProcessor, but we can be more
            // specific because we know exactly what attribute was being executed and caused the error
            if (!e.hasTemplateName()) {
                e.setTemplateName(tag.getTemplateName());
            }
            if (!e.hasLineAndCol()) {
                e.setLineAndCol(attribute.getLine(), attribute.getCol());
            }
            throw e;
        } catch (final Exception e) {
            throw new TemplateProcessingException(
                    "Error during execution of processor '" + StandardDefaultAttributesTagProcessor.class.getName() + "'",
                    tag.getTemplateName(), attribute.getLine(), attribute.getCol(), e);
        }

    }
}
