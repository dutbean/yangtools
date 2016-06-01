/*
 * Copyright (c) 2014 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.yangtools.yang.data.codec.gson;

import com.google.common.annotations.Beta;
import com.google.common.base.Preconditions;
import com.google.gson.JsonIOException;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.MalformedJsonException;
import java.io.Closeable;
import java.io.EOFException;
import java.io.Flushable;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import org.opendaylight.yangtools.yang.data.api.schema.stream.NormalizedNodeStreamWriter;
import org.opendaylight.yangtools.yang.data.util.AbstractNodeDataWithSchema;
import org.opendaylight.yangtools.yang.data.util.AnyXmlNodeDataWithSchema;
import org.opendaylight.yangtools.yang.data.util.CompositeNodeDataWithSchema;
import org.opendaylight.yangtools.yang.data.util.LeafListEntryNodeDataWithSchema;
import org.opendaylight.yangtools.yang.data.util.LeafListNodeDataWithSchema;
import org.opendaylight.yangtools.yang.data.util.LeafNodeDataWithSchema;
import org.opendaylight.yangtools.yang.data.util.ListEntryNodeDataWithSchema;
import org.opendaylight.yangtools.yang.data.util.ListNodeDataWithSchema;
import org.opendaylight.yangtools.yang.data.util.ParserStreamUtils;
import org.opendaylight.yangtools.yang.data.util.RpcAsContainer;
import org.opendaylight.yangtools.yang.data.util.SimpleNodeDataWithSchema;
import org.opendaylight.yangtools.yang.model.api.AnyXmlSchemaNode;
import org.opendaylight.yangtools.yang.model.api.ChoiceCaseNode;
import org.opendaylight.yangtools.yang.model.api.ChoiceSchemaNode;
import org.opendaylight.yangtools.yang.model.api.DataNodeContainer;
import org.opendaylight.yangtools.yang.model.api.DataSchemaNode;
import org.opendaylight.yangtools.yang.model.api.Module;
import org.opendaylight.yangtools.yang.model.api.RpcDefinition;
import org.opendaylight.yangtools.yang.model.api.SchemaContext;
import org.opendaylight.yangtools.yang.model.api.SchemaNode;
import org.opendaylight.yangtools.yang.model.api.YangModeledAnyXmlSchemaNode;

/**
 * This class parses JSON elements from a GSON JsonReader. It disallows multiple elements of the same name unlike the
 * default GSON JsonParser.
 */
@Beta
public final class JsonParserStream implements Closeable, Flushable {
    private final Deque<URI> namespaces = new ArrayDeque<>();
    private final NormalizedNodeStreamWriter writer;
    private final JSONCodecFactory codecs;
    private final SchemaContext schema;
    private final DataSchemaNode parentNode;

    private JsonParserStream(final NormalizedNodeStreamWriter writer, final SchemaContext schemaContext,
            final DataSchemaNode parentNode) {
        this.schema = Preconditions.checkNotNull(schemaContext);
        this.writer = Preconditions.checkNotNull(writer);
        this.codecs = JSONCodecFactory.create(schemaContext);
        this.parentNode = parentNode;
    }

    public static JsonParserStream create(final NormalizedNodeStreamWriter writer, final SchemaContext schemaContext,
            final SchemaNode parentNode ) {
        if (parentNode instanceof RpcDefinition) {
            return new JsonParserStream(writer, schemaContext, new RpcAsContainer((RpcDefinition) parentNode));
        }
        Preconditions.checkArgument(parentNode instanceof DataSchemaNode, "Instance of DataSchemaNode class awaited.");
        return new JsonParserStream(writer, schemaContext, (DataSchemaNode) parentNode);
    }

    public static JsonParserStream create(final NormalizedNodeStreamWriter writer, final SchemaContext schemaContext) {
        return new JsonParserStream(writer, schemaContext, schemaContext);
    }

    public JsonParserStream parse(final JsonReader reader) {
        // code copied from gson's JsonParser and Stream classes

        final boolean lenient = reader.isLenient();
        reader.setLenient(true);
        boolean isEmpty = true;
        try {
            reader.peek();
            isEmpty = false;
            final CompositeNodeDataWithSchema compositeNodeDataWithSchema = new CompositeNodeDataWithSchema(parentNode);
            read(reader, compositeNodeDataWithSchema);
            compositeNodeDataWithSchema.write(writer);

            return this;
        } catch (final EOFException e) {
            if (isEmpty) {
                return this;
            }
            // The stream ended prematurely so it is likely a syntax error.
            throw new JsonSyntaxException(e);
        } catch (final MalformedJsonException | NumberFormatException e) {
            throw new JsonSyntaxException(e);
        } catch (final IOException e) {
            throw new JsonIOException(e);
        } catch (StackOverflowError | OutOfMemoryError e) {
            throw new JsonParseException("Failed parsing JSON source: " + reader + " to Json", e);
        } finally {
            reader.setLenient(lenient);
        }
    }

    private void setValue(final AbstractNodeDataWithSchema parent, final String value) {
        Preconditions.checkArgument(parent instanceof SimpleNodeDataWithSchema, "Node %s is not a simple type",
                parent.getSchema().getQName());
        final SimpleNodeDataWithSchema parentSimpleNode = (SimpleNodeDataWithSchema) parent;
        Preconditions.checkArgument(parentSimpleNode.getValue() == null, "Node '%s' has already set its value to '%s'",
                parentSimpleNode.getSchema().getQName(), parentSimpleNode.getValue());

        final Object translatedValue = translateValueByType(value, parentSimpleNode.getSchema());
        parentSimpleNode.setValue(translatedValue);
    }

    public void read(final JsonReader in, AbstractNodeDataWithSchema parent) throws IOException {
        switch (in.peek()) {
        case STRING:
        case NUMBER:
            setValue(parent, in.nextString());
            break;
        case BOOLEAN:
            setValue(parent, Boolean.toString(in.nextBoolean()));
            break;
        case NULL:
            in.nextNull();
            setValue(parent, null);
            break;
        case BEGIN_ARRAY:
            in.beginArray();
            while (in.hasNext()) {
                if (parent instanceof LeafNodeDataWithSchema) {
                    read(in, parent);
                } else {
                    final AbstractNodeDataWithSchema newChild = newArrayEntry(parent);
                    read(in, newChild);
                }
            }
            in.endArray();
            return;
        case BEGIN_OBJECT:
            final Set<String> namesakes = new HashSet<>();
            in.beginObject();
            /*
             * This allows parsing of incorrectly /as showcased/
             * in testconf nesting of list items - eg.
             * lists with one value are sometimes serialized
             * without wrapping array.
             *
             */
            if (isArray(parent)) {
                parent = newArrayEntry(parent);
            }
            while (in.hasNext()) {
                final String jsonElementName = in.nextName();
                DataSchemaNode parentSchema = parent.getSchema();
                if (parentSchema instanceof YangModeledAnyXmlSchemaNode) {
                    parentSchema = ((YangModeledAnyXmlSchemaNode) parentSchema).getSchemaOfAnyXmlData();
                }
                final NamespaceAndName namespaceAndName = resolveNamespace(jsonElementName, parentSchema);
                final String localName = namespaceAndName.getName();
                addNamespace(namespaceAndName.getUri());
                if (namesakes.contains(jsonElementName)) {
                    throw new JsonSyntaxException("Duplicate name " + jsonElementName + " in JSON input.");
                }
                namesakes.add(jsonElementName);

                final Deque<DataSchemaNode> childDataSchemaNodes =
                        ParserStreamUtils.findSchemaNodeByNameAndNamespace(parentSchema, localName, getCurrentNamespace());
                if (childDataSchemaNodes.isEmpty()) {
                    throw new IllegalStateException("Schema for node with name " + localName + " and namespace "
                            + getCurrentNamespace() + " doesn't exist.");
                }

                final AbstractNodeDataWithSchema newChild = ((CompositeNodeDataWithSchema) parent).addChild(childDataSchemaNodes);
                /*
                 * FIXME:anyxml data shouldn't be skipped but should be loaded somehow.
                 * will be able to load anyxml which conforms to YANG data using these
                 * parser, for other anyxml will be harder.
                 */
                if (newChild instanceof AnyXmlNodeDataWithSchema) {
                    in.skipValue();
                } else {
                    read(in, newChild);
                }
                removeNamespace();
            }
            in.endObject();
            return;
        case END_DOCUMENT:
        case NAME:
        case END_OBJECT:
        case END_ARRAY:
            break;
        }
    }

    private static boolean isArray(final AbstractNodeDataWithSchema parent) {
        return parent instanceof ListNodeDataWithSchema || parent instanceof LeafListNodeDataWithSchema;
    }

    private static AbstractNodeDataWithSchema newArrayEntry(final AbstractNodeDataWithSchema parent) {
        AbstractNodeDataWithSchema newChild;
        if (parent instanceof ListNodeDataWithSchema) {
            newChild = new ListEntryNodeDataWithSchema(parent.getSchema());
        } else if (parent instanceof LeafListNodeDataWithSchema) {
            newChild = new LeafListEntryNodeDataWithSchema(parent.getSchema());
        } else {
            throw new IllegalStateException("Found an unexpected array nested under "+ parent.getSchema().getQName());
        }
        ((CompositeNodeDataWithSchema) parent).addChild(newChild);
        return newChild;
    }

    private Object translateValueByType(final String value, final DataSchemaNode node) {
        if (node instanceof AnyXmlSchemaNode) {
            /*
             *  FIXME: Figure out some YANG extension dispatch, which will
             *  reuse JSON parsing or XML parsing - anyxml is not well-defined in
             * JSON.
             */
            return value;
        }
        return codecs.codecFor(node).deserialize(value);
    }

    private void removeNamespace() {
        namespaces.pop();
    }

    private void addNamespace(final URI namespace) {
        namespaces.push(namespace);
    }

    private NamespaceAndName resolveNamespace(final String childName, final DataSchemaNode dataSchemaNode) {
        final int lastIndexOfColon = childName.lastIndexOf(':');
        String moduleNamePart = null;
        String nodeNamePart = null;
        URI namespace = null;
        if (lastIndexOfColon != -1) {
            moduleNamePart = childName.substring(0, lastIndexOfColon);
            nodeNamePart = childName.substring(lastIndexOfColon + 1);

            final Module m = schema.findModuleByName(moduleNamePart, null);
            namespace = m == null ? null : m.getNamespace();
        } else {
            nodeNamePart = childName;
        }

        if (namespace == null) {
            Set<URI> potentialUris = Collections.emptySet();
            potentialUris = resolveAllPotentialNamespaces(nodeNamePart, dataSchemaNode);
            if (potentialUris.contains(getCurrentNamespace())) {
                namespace = getCurrentNamespace();
            } else if (potentialUris.size() == 1) {
                namespace = potentialUris.iterator().next();
            } else if (potentialUris.size() > 1) {
                throw new IllegalStateException("Choose suitable module name for element "+nodeNamePart+":"+toModuleNames(potentialUris));
            } else if (potentialUris.isEmpty()) {
                throw new IllegalStateException("Schema node with name "+nodeNamePart+" wasn't found under "+dataSchemaNode.getQName()+".");
            }
        }

        return new NamespaceAndName(nodeNamePart, namespace);
    }

    private String toModuleNames(final Set<URI> potentialUris) {
        final StringBuilder builder = new StringBuilder();
        for (final URI potentialUri : potentialUris) {
            builder.append("\n");
            //FIXME how to get information about revision from JSON input? currently first available is used.
            builder.append(schema.findModuleByNamespace(potentialUri).iterator().next().getName());
        }
        return builder.toString();
    }

    private Set<URI> resolveAllPotentialNamespaces(final String elementName, final DataSchemaNode dataSchemaNode) {
        final Set<URI> potentialUris = new HashSet<>();
        final Set<ChoiceSchemaNode> choices = new HashSet<>();
        if (dataSchemaNode instanceof DataNodeContainer) {
            for (final DataSchemaNode childSchemaNode : ((DataNodeContainer) dataSchemaNode).getChildNodes()) {
                if (childSchemaNode instanceof ChoiceSchemaNode) {
                    choices.add((ChoiceSchemaNode)childSchemaNode);
                } else if (childSchemaNode.getQName().getLocalName().equals(elementName)) {
                    potentialUris.add(childSchemaNode.getQName().getNamespace());
                }
            }

            for (final ChoiceSchemaNode choiceNode : choices) {
                for (final ChoiceCaseNode concreteCase : choiceNode.getCases()) {
                    potentialUris.addAll(resolveAllPotentialNamespaces(elementName, concreteCase));
                }
            }
        }
        return potentialUris;
    }

    private URI getCurrentNamespace() {
        return namespaces.peek();
    }



    private static class NamespaceAndName {
        private final URI uri;
        private final String name;

        public NamespaceAndName(final String name, final URI uri) {
            this.name = name;
            this.uri = uri;
        }

        public String getName() {
            return name;
        }

        public URI getUri() {
            return uri;
        }
    }

    @Override
    public void flush() throws IOException {
        writer.flush();
    }

    @Override
    public void close() throws IOException {
        writer.flush();
        writer.close();
    }
}
