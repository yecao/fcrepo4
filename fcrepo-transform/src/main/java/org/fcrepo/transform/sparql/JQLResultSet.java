/**
 * Copyright 2013 DuraSpace, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.fcrepo.transform.sparql;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;
import com.hp.hpl.jena.graph.Node;
import com.hp.hpl.jena.query.QuerySolution;
import com.hp.hpl.jena.query.ResultSet;
import com.hp.hpl.jena.rdf.model.Literal;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.RDFNode;
import com.hp.hpl.jena.rdf.model.Resource;
import com.hp.hpl.jena.sparql.core.Var;
import com.hp.hpl.jena.sparql.engine.binding.Binding;
import org.fcrepo.kernel.rdf.GraphSubjects;
import org.slf4j.Logger;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.query.QueryResult;
import javax.jcr.query.Row;
import javax.jcr.query.RowIterator;
import java.util.Iterator;
import java.util.List;

import static com.google.common.base.Throwables.propagate;
import static com.hp.hpl.jena.rdf.model.ResourceFactory.createResource;
import static com.hp.hpl.jena.rdf.model.ResourceFactory.createTypedLiteral;
import static javax.jcr.PropertyType.BOOLEAN;
import static javax.jcr.PropertyType.DATE;
import static javax.jcr.PropertyType.DECIMAL;
import static javax.jcr.PropertyType.DOUBLE;
import static javax.jcr.PropertyType.LONG;
import static javax.jcr.PropertyType.PATH;
import static javax.jcr.PropertyType.REFERENCE;
import static javax.jcr.PropertyType.URI;
import static javax.jcr.PropertyType.WEAKREFERENCE;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Translate a JCR QueryResult to a SPARQL ResultSet
 *
 * @author cabeer
 */
public class JQLResultSet implements ResultSet {

    private final Logger logger = getLogger(JQLResultSet.class);

    private final RowIterator iterator;
    private Session session;
    private GraphSubjects subjects;
    private QueryResult queryResult;
    private int rowNumber = 0;

    /**
     * Translate a JCR QueryResult to a SPARQL ResultSet, respecting any
     * GraphSubjects translation for JCR Paths
     * @param subjects
     * @param queryResult
     * @throws RepositoryException
     */
    public JQLResultSet(final Session session,
                        final GraphSubjects subjects,
                        final QueryResult queryResult) throws RepositoryException {
        this.session = session;
        this.subjects = subjects;

        this.queryResult = queryResult;
        this.iterator = queryResult.getRows();
    }

    /**
     * Get the raw JCR query result
     * @return
     */
    public QueryResult getQueryResult() {
        return this.queryResult;
    }

    @Override
    public void remove() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean hasNext() {
        return iterator.hasNext();
    }

    @Override
    public QuerySolution next() {
        rowNumber++;
        final JQLQuerySolution jqlQuerySolution = new JQLQuerySolution(subjects, iterator.nextRow(), getResultVars());
        logger.trace("Getting QuerySolution (#{}): {}", rowNumber, jqlQuerySolution);

        return jqlQuerySolution;
    }

    @Override
    public QuerySolution nextSolution() {
        return next();
    }

    @Override
    public Binding nextBinding() {
        return (Binding)next();
    }

    @Override
    public int getRowNumber() {
        return rowNumber;
    }

    @Override
    public List<String> getResultVars() {
        try {
            return ImmutableList.copyOf(queryResult.getColumnNames());
        } catch (RepositoryException e) {
            throw propagate(e);
        }
    }

    @Override
    public Model getResourceModel() {
        return null;
    }

    private class JQLQuerySolution implements QuerySolution, Binding {
        private GraphSubjects subjects;
        private Row row;
        private List<String> columns;

        public JQLQuerySolution(final GraphSubjects subjects, final Row row, final List<String> columns) {
            this.subjects = subjects;
            this.row = row;
            this.columns = columns;
        }

        @Override
        public RDFNode get(String varName) {
            try {
                return getRDFNode(row.getValue(varName));
            } catch (Exception e) {
                propagate(e);
            }
            return null;
        }

        @Override
        public Resource getResource(String varName) {
            return get(varName).asResource();
        }

        @Override
        public Literal getLiteral(String varName) {
            return get(varName).asLiteral();
        }

        @Override
        public boolean contains(String varName) {
            try {
                final Value value = row.getValue(varName);
                return value != null;
            } catch (RepositoryException e) {
                return false;
            }
        }

        @Override
        public Iterator<String> varNames() {
            return columns.iterator();
        }

        @Override
        public Iterator<Var> vars() {
            return Iterators.transform(columns.iterator(), new Function<String, Var>() {
                @Override
                public Var apply(final String s) {
                    return Var.alloc(s);
                }
            });
        }

        @Override
        public boolean contains(Var var) {
            return contains(var.getName());
        }

        @Override
        public Node get(Var var) {
            return get(var.getName()).asNode();
        }

        @Override
        public int size() {
            return columns.size();
        }

        @Override
        public boolean isEmpty() {
            return columns.isEmpty();
        }

        /**
         * Copied from PropertyToTriple, but we need to return RDFNodes, and
         * don't have Property nodes.
         * @param v
         * @return
         * @throws Exception
         */
        private RDFNode getRDFNode(final Value v) {

            try {
                switch (v.getType()) {
                    case BOOLEAN:
                        return createTypedLiteral(v.getString());
                    case DATE:
                        return createTypedLiteral(v.getDate());
                    case DECIMAL:
                        return createTypedLiteral(v.getDecimal());
                    case DOUBLE:
                        return createTypedLiteral(v.getDouble());
                    case LONG:
                        return createTypedLiteral(v.getLong());
                    case URI:
                        return createResource(v.getString());
                    case PATH:
                        return subjects.getGraphSubject(v.getString());
                    case REFERENCE:
                    case WEAKREFERENCE:
                        // cheat and just return the UUID syntax
                        return subjects.getGraphSubject(session.getNodeByIdentifier(v.getString()));
                    default:
                        return createTypedLiteral(v.getString());
                }
            } catch (final RepositoryException e) {
                throw propagate(e);
            }
        }
    }
}
