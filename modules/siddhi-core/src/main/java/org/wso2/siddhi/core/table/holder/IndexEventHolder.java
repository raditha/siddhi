/*
 * Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.siddhi.core.table.holder;

import org.wso2.siddhi.core.event.ComplexEvent;
import org.wso2.siddhi.core.event.ComplexEventChunk;
import org.wso2.siddhi.core.event.stream.StreamEvent;
import org.wso2.siddhi.core.event.stream.StreamEventPool;
import org.wso2.siddhi.core.event.stream.converter.StreamEventConverter;
import org.wso2.siddhi.core.exception.OperationNotSupportedException;
import org.wso2.siddhi.query.api.expression.condition.Compare;

import java.util.*;

public class IndexEventHolder implements IndexedEventHolder {

    private StreamEventPool tableStreamEventPool;
    private StreamEventConverter eventConverter;
    private int primaryKeyPosition;
    private String primaryKeyAttribute;
    private Map<String, Integer> indexMetaData;
    private final TreeMap<Object, StreamEvent> primaryKeyData;
    private final Map<String, TreeMap<Object, Set<StreamEvent>>> indexData;

    public IndexEventHolder(StreamEventPool tableStreamEventPool, StreamEventConverter eventConverter,
                            int primaryKeyPosition, String primaryKeyAttribute, Map<String, Integer> indexMetaData) {
        this.tableStreamEventPool = tableStreamEventPool;
        this.eventConverter = eventConverter;
        this.primaryKeyPosition = primaryKeyPosition;
        this.primaryKeyAttribute = primaryKeyAttribute;
        this.indexMetaData = indexMetaData;

        if (primaryKeyAttribute != null) {
            primaryKeyData = new TreeMap<Object, StreamEvent>();
        } else {
            primaryKeyData = null;
        }
        if (indexMetaData.size() > 0) {
            indexData = new HashMap<String, TreeMap<Object, Set<StreamEvent>>>();
            for (String indexAttributeName : indexMetaData.keySet()) {
                indexData.put(indexAttributeName, new TreeMap<Object, Set<StreamEvent>>());
            }
        } else {
            indexData = null;
        }
    }

    @Override
    public void add(ComplexEventChunk<StreamEvent> addingEventChunk) {
        addingEventChunk.reset();
        while (addingEventChunk.hasNext()) {
            ComplexEvent complexEvent = addingEventChunk.next();
            StreamEvent streamEvent = tableStreamEventPool.borrowEvent();
            eventConverter.convertComplexEvent(complexEvent, streamEvent);
            add(streamEvent);
        }
    }

    private void add(StreamEvent streamEvent) {
        StreamEvent deletedEvent = null;
        if (primaryKeyData != null) {
            deletedEvent = primaryKeyData.put(streamEvent.getOutputData()[primaryKeyPosition], streamEvent);
        }

        if (indexData != null) {
            for (Map.Entry<String, Integer> indexEntry : indexMetaData.entrySet()) {
                TreeMap<Object, Set<StreamEvent>> indexMap = indexData.get(indexEntry.getKey());
                Object key = streamEvent.getOutputData()[indexEntry.getValue()];
                if (deletedEvent != null) {
                    Set<StreamEvent> values = indexMap.get(key);
                    values.remove(deletedEvent);
                    if (values.size() == 0) {
                        indexMap.remove(key);
                    }
                }
                Set<StreamEvent> values = indexMap.get(key);
                if (values == null) {
                    values = new HashSet<StreamEvent>();
                    values.add(streamEvent);
                    indexMap.put(streamEvent.getOutputData()[indexEntry.getValue()], values);
                } else {
                    values.add(streamEvent);
                }
            }
        }
    }

    @Override
    public boolean isSupportedIndex(String attribute, Compare.Operator operator) {
        return isAttributeIndexed(attribute);
    }

    @Override
    public boolean isAttributeIndexed(String attribute) {
        return (primaryKeyData != null && primaryKeyAttribute.equalsIgnoreCase(attribute)) || (indexData != null && indexMetaData.containsKey(attribute));
    }

    @Override
    public Set<StreamEvent> getAllEventSet() {
        if (primaryKeyData != null) {
            return new HashSet<StreamEvent>(primaryKeyData.values());
        } else if (indexData != null) {
            HashSet<StreamEvent> resultEventSet = new HashSet<StreamEvent>();
            Iterator<TreeMap<Object, Set<StreamEvent>>> iterator = indexData.values().iterator();
            if (iterator.hasNext()) {
                TreeMap<Object, Set<StreamEvent>> aIndexData = iterator.next();
                for (Set<StreamEvent> streamEvents : aIndexData.values()) {
                    resultEventSet.addAll(streamEvents);
                }
            }
            return resultEventSet;
        } else {
            return new HashSet<StreamEvent>();
        }
    }

    @Override
    public Set<StreamEvent> findEventSet(String attribute, Compare.Operator operator, Object value) {


        if (primaryKeyData != null && attribute.equals(primaryKeyAttribute)) {
            HashSet<StreamEvent> resultEventSet = new HashSet<StreamEvent>();
            StreamEvent resultEvent;

            switch (operator) {
                case LESS_THAN:
                    resultEventSet.addAll(primaryKeyData.headMap(value).values());
                    return resultEventSet;
                case GREATER_THAN:
                    resultEventSet.addAll(primaryKeyData.tailMap(value).values());
                    return resultEventSet;
                case LESS_THAN_EQUAL:
                    resultEventSet.addAll(primaryKeyData.headMap(value, true).values());
                    return resultEventSet;
                case GREATER_THAN_EQUAL:
                    resultEventSet.addAll(primaryKeyData.tailMap(value, true).values());
                    return resultEventSet;
                case EQUAL:
                    resultEvent = primaryKeyData.get(value);
                    if (resultEvent != null) {
                        resultEventSet.add(resultEvent);
                    }
                    return resultEventSet;
                case NOT_EQUAL:
                    if (primaryKeyData.size() > 0) {
                        resultEventSet = new HashSet<StreamEvent>(primaryKeyData.values());
                    } else {
                        resultEventSet = new HashSet<StreamEvent>();
                    }

                    resultEvent = primaryKeyData.get(value);
                    if (resultEvent != null) {
                        resultEventSet.remove(resultEvent);
                    }
                    return resultEventSet;
            }
        } else {
            HashSet<StreamEvent> resultEventSet = new HashSet<StreamEvent>();
            TreeMap<Object, Set<StreamEvent>> currentIndexedData = indexData.get(attribute);

            Set<StreamEvent> resultEvents;
            switch (operator) {

                case LESS_THAN:
                    for (Set<StreamEvent> eventSet : currentIndexedData.headMap(value).values()) {
                        resultEventSet.addAll(eventSet);
                    }
                    return resultEventSet;
                case GREATER_THAN:
                    for (Set<StreamEvent> eventSet : currentIndexedData.tailMap(value).values()) {
                        resultEventSet.addAll(eventSet);
                    }
                    return resultEventSet;
                case LESS_THAN_EQUAL:
                    for (Set<StreamEvent> eventSet : currentIndexedData.headMap(value, true).values()) {
                        resultEventSet.addAll(eventSet);
                    }
                    return resultEventSet;
                case GREATER_THAN_EQUAL:
                    for (Set<StreamEvent> eventSet : currentIndexedData.tailMap(value, true).values()) {
                        resultEventSet.addAll(eventSet);
                    }
                    return resultEventSet;
                case EQUAL:
                    resultEvents = currentIndexedData.get(value);
                    if (resultEvents != null) {
                        resultEventSet.addAll(resultEvents);
                    }
                    return resultEventSet;
                case NOT_EQUAL:
                    if (currentIndexedData.size() > 0) {
                        resultEventSet = new HashSet<StreamEvent>();
                        for (Set<StreamEvent> eventSet : currentIndexedData.values()) {
                            resultEventSet.addAll(eventSet);
                        }
                    } else {
                        resultEventSet = new HashSet<StreamEvent>();
                    }

                    resultEvents = currentIndexedData.get(value);
                    if (resultEvents != null) {
                        resultEventSet.removeAll(resultEvents);
                    }
                    return resultEventSet;
            }
        }
        throw new OperationNotSupportedException(operator + " not supported for '" + value + "' by " + getClass().getName());
    }

    @Override
    public void deleteAll() {
        if (primaryKeyData != null) {
            primaryKeyData.clear();
        }
        if (indexData != null) {
            for (TreeMap<Object, Set<StreamEvent>> aIndexedData : indexData.values()) {
                aIndexedData.clear();
            }
        }
    }

    @Override
    public void deleteAll(Set<StreamEvent> candidateEventSet) {
        for (StreamEvent streamEvent : candidateEventSet) {
            if (primaryKeyData != null) {
                StreamEvent deletedEvent = primaryKeyData.remove(streamEvent.getOutputData()[primaryKeyPosition]);
                if (indexData != null) {
                    deleteFromIndexes(deletedEvent);
                }
            } else if (indexData != null) {
                deleteFromIndexes(streamEvent);
            }
        }
    }

    @Override
    public void delete(String attribute, Compare.Operator operator, Object value) {
        if (primaryKeyData != null && attribute.equals(primaryKeyAttribute)) {
            switch (operator) {

                case LESS_THAN:
                    for (Iterator<StreamEvent> iterator = primaryKeyData.headMap(value).values().iterator();
                         iterator.hasNext(); ) {
                        StreamEvent toDeleteEvent = iterator.next();
                        iterator.remove();
                        deleteFromIndexes(toDeleteEvent);
                    }
                    return;
                case GREATER_THAN:
                    for (Iterator<StreamEvent> iterator = primaryKeyData.tailMap(value).values().iterator();
                         iterator.hasNext(); ) {
                        StreamEvent toDeleteEvent = iterator.next();
                        iterator.remove();
                        deleteFromIndexes(toDeleteEvent);
                    }
                    return;
                case LESS_THAN_EQUAL:
                    for (Iterator<StreamEvent> iterator = primaryKeyData.headMap(value, true).values().iterator();
                         iterator.hasNext(); ) {
                        StreamEvent toDeleteEvent = iterator.next();
                        iterator.remove();
                        deleteFromIndexes(toDeleteEvent);
                    }
                    return;
                case GREATER_THAN_EQUAL:
                    for (Iterator<StreamEvent> iterator = primaryKeyData.tailMap(value, true).values().iterator();
                         iterator.hasNext(); ) {
                        StreamEvent toDeleteEvent = iterator.next();
                        iterator.remove();
                        deleteFromIndexes(toDeleteEvent);
                    }
                    return;
                case EQUAL:
                    StreamEvent deletedEvent = primaryKeyData.remove(value);
                    if (deletedEvent != null) {
                        deleteFromIndexes(deletedEvent);
                    }
                    return;
                case NOT_EQUAL:
                    StreamEvent streamEvent = primaryKeyData.get(value);
                    deleteAll();
                    if (streamEvent != null) {
                        add(streamEvent);
                    }
                    return;
            }
        } else {
            switch (operator) {

                case LESS_THAN:
                    for (Iterator<Set<StreamEvent>> iterator = indexData.get(attribute).headMap(value).values().iterator();
                         iterator.hasNext(); ) {
                        Set<StreamEvent> deletedEventSet = iterator.next();
                        deleteFromIndexesAndPrimaryKey(attribute, deletedEventSet);
                        iterator.remove();
                    }
                    return;
                case GREATER_THAN:
                    for (Iterator<Set<StreamEvent>> iterator = indexData.get(attribute).tailMap(value).values().iterator();
                         iterator.hasNext(); ) {
                        Set<StreamEvent> deletedEventSet = iterator.next();
                        deleteFromIndexesAndPrimaryKey(attribute, deletedEventSet);
                        iterator.remove();
                    }
                    return;
                case LESS_THAN_EQUAL:
                    for (Iterator<Set<StreamEvent>> iterator = indexData.get(attribute).headMap(value, true).values().iterator();
                         iterator.hasNext(); ) {
                        Set<StreamEvent> deletedEventSet = iterator.next();
                        deleteFromIndexesAndPrimaryKey(attribute, deletedEventSet);
                        iterator.remove();
                    }
                    return;
                case GREATER_THAN_EQUAL:
                    for (Iterator<Set<StreamEvent>> iterator = indexData.get(attribute).tailMap(value, true).values().iterator();
                         iterator.hasNext(); ) {
                        Set<StreamEvent> deletedEventSet = iterator.next();
                        deleteFromIndexesAndPrimaryKey(attribute, deletedEventSet);
                        iterator.remove();
                    }
                    return;
                case EQUAL:
                    Set<StreamEvent> deletedEventSet = indexData.get(attribute).remove(value);
                    if (deletedEventSet != null && deletedEventSet.size() > 0) {
                        deleteFromIndexesAndPrimaryKey(attribute, deletedEventSet);
                    }
                    return;
                case NOT_EQUAL:
                    Set<StreamEvent> matchingEventSet = indexData.get(attribute).get(value);
                    deleteAll();
                    for (StreamEvent matchingEvent : matchingEventSet) {
                        add(matchingEvent);
                    }
                    return;
            }
        }
        throw new OperationNotSupportedException(operator + " not supported for '" + value + "' by " + getClass().getName());
    }


    private void deleteFromIndexesAndPrimaryKey(String currentAttribute, Set<StreamEvent> deletedEventSet) {
        for (StreamEvent deletedEvent : deletedEventSet) {
            primaryKeyData.remove(deletedEvent.getOutputData()[primaryKeyPosition]);
            for (Map.Entry<String, Integer> indexEntry : indexMetaData.entrySet()) {
                if (!currentAttribute.equals(indexEntry.getKey())) {
                    TreeMap<Object, Set<StreamEvent>> indexMap = indexData.get(indexEntry.getKey());
                    Object key = deletedEvent.getOutputData()[indexEntry.getValue()];
                    Set<StreamEvent> values = indexMap.get(key);
                    values.remove(deletedEvent);
                    if (values.size() == 0) {
                        indexMap.remove(key);
                    }
                }
            }
        }
    }

    private void deleteFromIndexes(StreamEvent toDeleteEvent) {
        for (Map.Entry<String, Integer> indexEntry : indexMetaData.entrySet()) {
            TreeMap<Object, Set<StreamEvent>> indexMap = indexData.get(indexEntry.getKey());
            Object key = toDeleteEvent.getOutputData()[indexEntry.getValue()];
            Set<StreamEvent> values = indexMap.get(key);
            values.remove(toDeleteEvent);
            if (values.size() == 0) {
                indexMap.remove(key);
            }
        }
    }
}
