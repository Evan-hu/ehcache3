/*
 * Copyright Terracotta, Inc.
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

package org.ehcache.clustered.server.internal.messages;

import org.ehcache.clustered.common.internal.messages.ChainCodec;
import org.ehcache.clustered.common.internal.messages.EhcacheEntityMessage;
import org.ehcache.clustered.common.internal.store.Chain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.entity.MessageCodecException;
import org.terracotta.entity.SyncMessageCodec;
import org.terracotta.runnel.Struct;
import org.terracotta.runnel.decoding.Enm;
import org.terracotta.runnel.decoding.StructArrayDecoder;
import org.terracotta.runnel.decoding.StructDecoder;
import org.terracotta.runnel.encoding.StructEncoder;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.ehcache.clustered.common.internal.messages.ChainCodec.CHAIN_ENCODER_FUNCTION;
import static org.ehcache.clustered.common.internal.messages.ChainCodec.CHAIN_STRUCT;
import static org.ehcache.clustered.common.internal.messages.MessageCodecUtils.KEY_FIELD;
import static org.ehcache.clustered.server.internal.messages.SyncMessageType.DATA;
import static org.ehcache.clustered.server.internal.messages.SyncMessageType.SYNC_MESSAGE_TYPE_FIELD_INDEX;
import static org.ehcache.clustered.server.internal.messages.SyncMessageType.SYNC_MESSAGE_TYPE_FIELD_NAME;
import static org.ehcache.clustered.server.internal.messages.SyncMessageType.SYNC_MESSAGE_TYPE_MAPPING;
import static org.terracotta.runnel.StructBuilder.newStructBuilder;

public class EhcacheSyncMessageCodec implements SyncMessageCodec<EhcacheEntityMessage> {

  private static final Logger LOGGER = LoggerFactory.getLogger(EhcacheSyncMessageCodec.class);

  private static final String CHAIN_FIELD = "chain";
  private static final String CHAIN_MAP_ENTRIES_SUB_STRUCT = "entries";

  private static final Struct CHAIN_MAP_ENTRY_STRUCT = newStructBuilder()
    .int64(KEY_FIELD, 10)
    .struct(CHAIN_FIELD, 20, CHAIN_STRUCT)
    .build();

  private static final Struct DATA_SYNC_STRUCT = newStructBuilder()
    .enm(SYNC_MESSAGE_TYPE_FIELD_NAME, SYNC_MESSAGE_TYPE_FIELD_INDEX, SYNC_MESSAGE_TYPE_MAPPING)
    .structs(CHAIN_MAP_ENTRIES_SUB_STRUCT, 20, CHAIN_MAP_ENTRY_STRUCT)
    .build();

  public EhcacheSyncMessageCodec() {
  }

  @Override
  public byte[] encode(final int concurrencyKey, final EhcacheEntityMessage message) throws MessageCodecException {
    if (message instanceof EhcacheSyncMessage) {
      EhcacheSyncMessage syncMessage = (EhcacheSyncMessage) message;
      StructEncoder<Void> encoder;
      switch (syncMessage.getMessageType()) {
        case DATA: {
          encoder = DATA_SYNC_STRUCT.encoder();
          EhcacheDataSyncMessage dataSyncMessage = (EhcacheDataSyncMessage)syncMessage;
          encoder.enm(SYNC_MESSAGE_TYPE_FIELD_NAME, DATA);
          encoder.structs(CHAIN_MAP_ENTRIES_SUB_STRUCT,
            dataSyncMessage.getChainMap().entrySet(), (entryEncoder, entry) -> {
              entryEncoder.int64(KEY_FIELD, entry.getKey());
              entryEncoder.struct(CHAIN_FIELD, entry.getValue(), CHAIN_ENCODER_FUNCTION);
            });
          return encoder.encode().array();
        }
        default:
          throw new IllegalArgumentException("Sync message codec can not encode " + syncMessage.getMessageType());
      }
    } else {
      throw new IllegalArgumentException(this.getClass().getName() + " can not encode " + message + " which is not a " + EhcacheSyncMessage.class);
    }
  }

  @Override
  public EhcacheSyncMessage decode(final int concurrencyKey, final byte[] payload) throws MessageCodecException {
    ByteBuffer message = ByteBuffer.wrap(payload);
    StructDecoder<Void> decoder = DATA_SYNC_STRUCT.decoder(message);
    Enm<SyncMessageType> enm = decoder.enm(SYNC_MESSAGE_TYPE_FIELD_NAME);
    if (!enm.isFound()) {
      throw new AssertionError("Invalid message format - misses the message type field");
    }
    if (!enm.isValid()) {
      LOGGER.warn("Unknown sync message received - ignoring {}", enm.raw());
      return null;
    }

    switch (enm.get()) {
      case DATA:
        message.rewind();
        decoder = DATA_SYNC_STRUCT.decoder(message);
        Map<Long, Chain> chainMap = decodeChainMapEntries(decoder);
        return new EhcacheDataSyncMessage(chainMap);
      default:
        throw new AssertionError("Cannot happen given earlier checks");
    }
  }

  private Map<Long, Chain> decodeChainMapEntries(StructDecoder<Void> decoder) {
    Map<Long, Chain> chainMap = new HashMap<>();

    StructArrayDecoder<? extends StructDecoder<?>> entriesDecoder = decoder.structs(CHAIN_MAP_ENTRIES_SUB_STRUCT);
    if (entriesDecoder != null) {
      for (int i = 0; i < entriesDecoder.length(); i++) {
        StructDecoder<?> entryDecoder = entriesDecoder.next();
        Long key = entryDecoder.int64(KEY_FIELD);
        StructDecoder<?> chainDecoder = entryDecoder.struct(CHAIN_FIELD);
        Chain chain = ChainCodec.decode(chainDecoder);
        chainMap.put(key, chain);
        entryDecoder.end();
      }
    }
    return chainMap;
  }

}
