/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.store.spark;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.table.catalog.ObjectPath;
import org.apache.flink.table.store.file.catalog.Catalog;
import org.apache.flink.table.store.file.catalog.CatalogFactory;
import org.apache.flink.table.store.file.schema.SchemaChange;
import org.apache.flink.util.Preconditions;

import org.apache.spark.sql.catalyst.analysis.NoSuchNamespaceException;
import org.apache.spark.sql.catalyst.analysis.NoSuchTableException;
import org.apache.spark.sql.connector.catalog.Identifier;
import org.apache.spark.sql.connector.catalog.NamespaceChange;
import org.apache.spark.sql.connector.catalog.SupportsNamespaces;
import org.apache.spark.sql.connector.catalog.Table;
import org.apache.spark.sql.connector.catalog.TableCatalog;
import org.apache.spark.sql.connector.catalog.TableChange;
import org.apache.spark.sql.connector.catalog.TableChange.AddColumn;
import org.apache.spark.sql.connector.catalog.TableChange.RemoveProperty;
import org.apache.spark.sql.connector.catalog.TableChange.SetProperty;
import org.apache.spark.sql.connector.catalog.TableChange.UpdateColumnType;
import org.apache.spark.sql.connector.expressions.Transform;
import org.apache.spark.sql.types.StructType;
import org.apache.spark.sql.util.CaseInsensitiveStringMap;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.apache.flink.table.store.spark.SparkTypeUtils.toFlinkType;

/** Spark {@link TableCatalog} for table store. */
public class SparkCatalog implements TableCatalog, SupportsNamespaces {

    private String name = null;
    private Catalog catalog = null;

    @Override
    public void initialize(String name, CaseInsensitiveStringMap options) {
        this.name = name;
        this.catalog = CatalogFactory.createCatalog(Configuration.fromMap(options));
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String[][] listNamespaces() {
        List<String> databases = catalog.listDatabases();
        String[][] namespaces = new String[databases.size()][];
        for (int i = 0; i < databases.size(); i++) {
            namespaces[i] = new String[] {databases.get(i)};
        }
        return namespaces;
    }

    @Override
    public String[][] listNamespaces(String[] namespace) throws NoSuchNamespaceException {
        if (namespace.length == 0) {
            return listNamespaces();
        }
        if (!isValidateNamespace(namespace)) {
            throw new NoSuchNamespaceException(namespace);
        }
        return new String[0][];
    }

    @Override
    public Map<String, String> loadNamespaceMetadata(String[] namespace) {
        return Collections.emptyMap();
    }

    @Override
    public Identifier[] listTables(String[] namespace) throws NoSuchNamespaceException {
        Preconditions.checkArgument(
                isValidateNamespace(namespace),
                "Missing database in namespace: %s",
                Arrays.toString(namespace));

        try {
            return catalog.listTables(namespace[0]).stream()
                    .map(table -> Identifier.of(namespace, table))
                    .toArray(Identifier[]::new);
        } catch (Catalog.DatabaseNotExistException e) {
            throw new NoSuchNamespaceException(namespace);
        }
    }

    @Override
    public SparkTable loadTable(Identifier ident) throws NoSuchTableException {
        try {
            return new SparkTable(catalog.getTable(objectPath(ident)));
        } catch (Catalog.TableNotExistException e) {
            throw new NoSuchTableException(ident);
        }
    }

    @Override
    public Table alterTable(Identifier ident, TableChange... changes) throws NoSuchTableException {
        List<SchemaChange> schemaChanges =
                Arrays.stream(changes).map(this::toSchemaChange).collect(Collectors.toList());
        try {
            catalog.alterTable(objectPath(ident), schemaChanges, false);
            return loadTable(ident);
        } catch (Catalog.TableNotExistException e) {
            throw new NoSuchTableException(ident);
        }
    }

    private SchemaChange toSchemaChange(TableChange change) {
        if (change instanceof SetProperty) {
            SetProperty set = (SetProperty) change;
            return SchemaChange.setOption(set.property(), set.value());
        } else if (change instanceof RemoveProperty) {
            return SchemaChange.removeOption(((RemoveProperty) change).property());
        } else if (change instanceof AddColumn) {
            AddColumn add = (AddColumn) change;
            validateAlterNestedField(add.fieldNames());
            return SchemaChange.addColumn(
                    add.fieldNames()[0],
                    toFlinkType(add.dataType()),
                    add.isNullable(),
                    add.comment());
        } else if (change instanceof UpdateColumnType) {
            UpdateColumnType update = (UpdateColumnType) change;
            validateAlterNestedField(update.fieldNames());
            return SchemaChange.updateColumnType(
                    update.fieldNames()[0], toFlinkType(update.newDataType()));
        } else {
            throw new UnsupportedOperationException(
                    "Change is not supported: " + change.getClass());
        }
    }

    private void validateAlterNestedField(String[] fieldNames) {
        if (fieldNames.length > 1) {
            throw new UnsupportedOperationException(
                    "Alter nested column is not supported: " + Arrays.toString(fieldNames));
        }
    }

    private boolean isValidateNamespace(String[] namespace) {
        return namespace.length == 1;
    }

    private ObjectPath objectPath(Identifier ident) throws NoSuchTableException {
        if (!isValidateNamespace(ident.namespace())) {
            throw new NoSuchTableException(ident);
        }

        return new ObjectPath(ident.namespace()[0], ident.name());
    }

    // --------------------- unsupported methods ----------------------------

    @Override
    public void createNamespace(String[] namespace, Map<String, String> metadata) {
        throw new UnsupportedOperationException("Create namespace in Spark is not supported yet.");
    }

    @Override
    public void alterNamespace(String[] namespace, NamespaceChange... changes) {
        throw new UnsupportedOperationException("Alter namespace in Spark is not supported yet.");
    }

    /**
     * Drop a namespace from the catalog, recursively dropping all objects within the namespace.
     * This interface implementation only supports the Spark 3.0, 3.1 and 3.2.
     *
     * <p>If the catalog implementation does not support this operation, it may throw {@link
     * UnsupportedOperationException}.
     *
     * @param namespace a multi-part namespace
     * @return true if the namespace was dropped
     * @throws UnsupportedOperationException If drop is not a supported operation
     */
    public boolean dropNamespace(String[] namespace) {
        return dropNamespace(namespace, true);
    }

    /**
     * Drop a namespace from the catalog with cascade mode, recursively dropping all objects within
     * the namespace if cascade is true. This interface implementation supports the Spark 3.3+.
     *
     * <p>If the catalog implementation does not support this operation, it may throw {@link
     * UnsupportedOperationException}.
     *
     * @param namespace a multi-part namespace
     * @param cascade When true, deletes all objects under the namespace
     * @return true if the namespace was dropped
     * @throws UnsupportedOperationException If drop is not a supported operation
     */
    public boolean dropNamespace(String[] namespace, boolean cascade) {
        throw new UnsupportedOperationException("Drop namespace in Spark is not supported yet.");
    }

    @Override
    public Table createTable(
            Identifier ident,
            StructType schema,
            Transform[] partitions,
            Map<String, String> properties) {
        throw new UnsupportedOperationException("Create table in Spark is not supported yet.");
    }

    @Override
    public boolean dropTable(Identifier ident) {
        throw new UnsupportedOperationException("Drop table in Spark is not supported yet.");
    }

    @Override
    public void renameTable(Identifier oldIdent, Identifier newIdent) {
        throw new UnsupportedOperationException();
    }
}
