/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package io.github.sb2k16.jobboard.spec;

import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * XML paths are XPath 1.0, resolved against the document for the page and against each item
 * element for fields ({@code title}, {@code g:id}, {@code pubDate[last()]}). Namespace prefixes
 * come from the spec. External entities and DTD loading are off.
 */
final class XmlPage implements Page {
    private final Document document;
    private final XPath xpath;

    private XmlPage(final Document document, final XPath xpath) {
        this.document = document;
        this.xpath = xpath;
    }

    static XmlPage parse(final String body, final Map<String, String> namespaces) throws IOException, SAXException {
        final DocumentBuilder builder;
        try {
            builder = factory().newDocumentBuilder();
        } catch (final ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
        builder.setErrorHandler(null);
        final Document document = builder.parse(new InputSource(new StringReader(stripLeading(body))));
        final XPath xpath = XPathFactory.newInstance().newXPath();
        xpath.setNamespaceContext(new SpecNamespaces(namespaces));
        return new XmlPage(document, xpath);
    }

    private static DocumentBuilderFactory factory() throws ParserConfigurationException {
        final DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        return f;
    }

    /** Feeds sometimes start with whitespace or a byte-order mark before the XML declaration. */
    private static String stripLeading(final String body) {
        int i = 0;
        while (i < body.length() && (Character.isWhitespace(body.charAt(i)) || body.charAt(i) == '﻿')) {
            i++;
        }
        return body.substring(i);
    }

    @Override
    public boolean exists(final String path) {
        return !evaluate(document, path).isEmpty();
    }

    @Override
    public List<Node> items(final String path) {
        final NodeList nodes;
        try {
            nodes = (NodeList) xpath.evaluate(path, document, XPathConstants.NODESET);
        } catch (final XPathExpressionException e) {
            throw new IllegalArgumentException("Bad items XPath '" + path + "': " + e.getMessage(), e);
        }
        final List<Node> items = new ArrayList<>(nodes.getLength());
        for (int i = 0; i < nodes.getLength(); i++) {
            final org.w3c.dom.Node item = nodes.item(i);
            items.add(p -> evaluate(item, p));
        }
        return items;
    }

    @Override
    public List<String> values(final String path) {
        return evaluate(document, path);
    }

    private List<String> evaluate(final Object context, final String path) {
        final List<String> out = new ArrayList<>();
        try {
            final NodeList nodes = (NodeList) xpath.evaluate(path, context, XPathConstants.NODESET);
            for (int i = 0; i < nodes.getLength(); i++) {
                out.add(nodes.item(i).getTextContent());
            }
            return out;
        } catch (final XPathExpressionException notANodeSet) {
            // string(...), count(...) and similar return a value, not nodes.
        }
        try {
            final String value = (String) xpath.evaluate(path, context, XPathConstants.STRING);
            if (value != null && !value.isEmpty()) {
                out.add(value);
            }
            return out;
        } catch (final XPathExpressionException e) {
            throw new IllegalArgumentException("Bad XPath '" + path + "': " + e.getMessage(), e);
        }
    }

    private static final class SpecNamespaces implements NamespaceContext {
        private final Map<String, String> prefixes;

        SpecNamespaces(final Map<String, String> prefixes) {
            this.prefixes = prefixes;
        }

        @Override
        public String getNamespaceURI(final String prefix) {
            return prefixes.getOrDefault(prefix, XMLConstants.NULL_NS_URI);
        }

        @Override
        public String getPrefix(final String uri) {
            return prefixes.entrySet().stream().filter(e -> e.getValue().equals(uri)).map(Map.Entry::getKey)
                    .findFirst().orElse(null);
        }

        @Override
        public Iterator<String> getPrefixes(final String uri) {
            final String prefix = getPrefix(uri);
            return prefix == null ? Collections.emptyIterator() : List.of(prefix).iterator();
        }
    }
}
