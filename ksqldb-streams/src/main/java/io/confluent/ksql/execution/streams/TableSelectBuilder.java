/*
 * Copyright 2019 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.execution.streams;

import io.confluent.ksql.GenericRow;
import io.confluent.ksql.execution.context.QueryContext;
import io.confluent.ksql.execution.context.QueryContext.Stacker;
import io.confluent.ksql.execution.materialization.MaterializationInfo;
import io.confluent.ksql.execution.plan.Formats;
import io.confluent.ksql.execution.plan.KTableHolder;
import io.confluent.ksql.execution.plan.TableSelect;
import io.confluent.ksql.execution.runtime.RuntimeBuildContext;
import io.confluent.ksql.execution.streams.transform.KsTransformer;
import io.confluent.ksql.execution.transform.KsqlTransformer;
import io.confluent.ksql.execution.transform.select.SelectValueMapper;
import io.confluent.ksql.execution.transform.select.Selection;
import io.confluent.ksql.logging.processing.ProcessingLogger;
import io.confluent.ksql.schema.ksql.LogicalSchema;
import io.confluent.ksql.schema.ksql.PhysicalSchema;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Named;
import org.apache.kafka.streams.state.KeyValueStore;

public final class TableSelectBuilder {
  private static final String PROJECT_OP = "Sta";

  private TableSelectBuilder() {
  }

  @SuppressWarnings("unchecked")
  public static <K> KTableHolder<K> build(
      final KTableHolder<K> table,
      final TableSelect<K> step,
      final RuntimeBuildContext buildContext,
      final boolean forceMaterialization,
      final Formats formats,
      final MaterializedFactory materializedFactory
  ) {
    final LogicalSchema sourceSchema = table.getSchema();
    final QueryContext queryContext = step.getProperties().getQueryContext();

    final Selection<K> selection = Selection.of(
            sourceSchema,
            step.getKeyColumnNames(),
            step.getSelectExpressions(),
            buildContext.getKsqlConfig(),
            buildContext.getFunctionRegistry()
    );

    final SelectValueMapper<K> selectMapper = selection.getMapper();

    final ProcessingLogger logger = buildContext.getProcessingLogger(queryContext);

    final Named selectName = Named.as(StreamsUtil.buildOpName(queryContext));

    if (forceMaterialization) {
      final PhysicalSchema physicalSchema = PhysicalSchema.from(
              selection.getSchema(),
              formats.getKeyFeatures(),
              formats.getValueFeatures()
      );

      final Serde<K> keySerde = (Serde<K>) buildContext.buildKeySerde(
              formats.getKeyFormat(),
              physicalSchema,
              queryContext
      );
      final Serde<GenericRow> valSerde = buildContext.buildValueSerde(
              formats.getValueFormat(),
              physicalSchema,
              queryContext
      );
      final Stacker stacker = Stacker.of(step.getProperties().getQueryContext());
      final String stateStoreName = StreamsUtil.buildOpName(
              stacker.push(PROJECT_OP).getQueryContext());
      final Materialized<K, GenericRow, KeyValueStore<Bytes, byte[]>> materialized =
              materializedFactory.create(
                      keySerde,
                      valSerde,
                      stateStoreName
              );
      final KTable<K, GenericRow> transFormedTable = table.getTable().transformValues(
          () -> new KsTransformer<>(selectMapper.getTransformer(logger)),
          materialized, Named.as(stateStoreName + "johns")
      );

      return KTableHolder.materialized(
              transFormedTable,
              selection.getSchema(),
              table.getExecutionKeyFactory(),
              MaterializationInfo.builder(stateStoreName, selection.getSchema())
      );
    }

    return table
            .withTable(
                    table.getTable().transformValues(
                        () -> new KsTransformer<>(selectMapper.getTransformer(logger)),
                            selectName
                    ),
                    selection.getSchema()
            )
            .withMaterialization(
                    table.getMaterializationBuilder().map(b -> b.map(
                        pl -> (KsqlTransformer<Object, GenericRow>) selectMapper.getTransformer(pl),
                        selection.getSchema(),
                        queryContext
                    ))
            );
  }
}



