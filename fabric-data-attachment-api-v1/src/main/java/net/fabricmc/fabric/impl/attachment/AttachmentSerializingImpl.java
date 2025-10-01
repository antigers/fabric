/*
 * Copyright (c) 2016, 2017, 2018, 2019 FabricMC
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

package net.fabricmc.fabric.impl.attachment;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.storage.NbtReadView;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.Identifier;

import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;

public class AttachmentSerializingImpl {
	private static final Logger LOGGER = LoggerFactory.getLogger("fabric-data-attachment-api-v1");

	private static final Codec<AttachmentType<?>> TYPE_CODEC = Identifier.CODEC.comapFlatMap(id -> {
		AttachmentType<?> type = AttachmentRegistryImpl.get(id);
		return type == null ? DataResult.error(() -> "Found unknown attachment type " + id)
				: !type.isPersistent() ? DataResult.error(() -> "Found non-permanent attachment type " + id)
				: DataResult.success(type);
	}, AttachmentType::identifier);

	private static final Codec<IdentityHashMap<AttachmentType<?>, Object>> SERIALIZATION_CODEC = getCodec(type -> {
		Codec<?> persistenceCodec = type.persistenceCodec();
		if (persistenceCodec != null) {
			return persistenceCodec;
		}
		return NbtCompound.CODEC.comapFlatMap(
				nbtCompound -> DataResult.error(() -> "This codec can only be used for serialization"),
				attachmentData -> {
					BiConsumer<?, WriteView> serializer = type.persistenceSerializer();
					Objects.requireNonNull(
							serializer, "persistenceSerializer cannot be null when trying to persist an attachment without persistenceCodec"
					);
					return serializeAttachment(attachmentData, (BiConsumer<Object, WriteView>) serializer);
				}
		);
	});

	private static Codec<IdentityHashMap<AttachmentType<?>, Object>> getCodec(Function<AttachmentType<?>, Codec<?>> valueCodecFunction) {
		return Codec.dispatchedMap(TYPE_CODEC, valueCodecFunction)
				.promotePartial(error -> LOGGER.warn("Skipping invalid attachments: {}", error))
				.xmap(
						IdentityHashMap::new,
						Function.identity()
				);
	}

	public static void serializeAttachmentData(WriteView view, @Nullable IdentityHashMap<AttachmentType<?>, Object> attachments) {
		if (attachments == null || attachments.isEmpty()) {
			return;
		}

		IdentityHashMap<AttachmentType<?>, Object> attachmentsToSerialize = attachments.entrySet().stream()
				.filter(entry -> entry.getKey().isPersistent())
				.collect(Collectors.toMap(
						Map.Entry::getKey,
						Map.Entry::getValue,
						(v1, v2) -> v1,
						IdentityHashMap::new
				));

		if (attachmentsToSerialize.isEmpty()) {
			return;
		}

		view.put(AttachmentTarget.NBT_ATTACHMENT_KEY, SERIALIZATION_CODEC, attachmentsToSerialize);
	}

	@Nullable
	public static IdentityHashMap<AttachmentType<?>, Object> deserializeAttachmentData(AttachmentTarget attachmentTarget, @Nullable ReadView data) {
		if (data == null) {
			return null;
		}
		return data.read(AttachmentTarget.NBT_ATTACHMENT_KEY, getCodec(type -> {
					Codec<?> persistenceCodec = type.persistenceCodec();
					if (persistenceCodec != null) {
						return persistenceCodec;
					}
					return NbtCompound.CODEC.flatComapMap(
							nbtCompound -> applyPersistenceDeserializer(attachmentTarget, type, nbtCompound),
							attachmentData -> DataResult.error(() -> "This codec can only be used for deserialization")
					);
				}))
				.filter(m -> !m.isEmpty())
				.orElse(null);
	}

	private static <A> A applyPersistenceDeserializer(AttachmentTarget target, AttachmentType<A> type, NbtCompound nbtCompound) {
		BiConsumer<A, ReadView> deserializer = type.persistenceDeserializer();
		Objects.requireNonNull(
				deserializer, "persistenceDeserializer cannot be null when trying to persist an attachment without persistenceCodec"
		);
		A attachmentData;
		Function<AttachmentTarget, A> targetedInitializer = type.targetedInitializer();
		if (targetedInitializer != null) {
			attachmentData = targetedInitializer.apply(target);
		} else {
			Supplier<A> initializer = type.initializer();
			Objects.requireNonNull(
					initializer, "either initializer or targetedInitializer must be present when using persistenceDeserializer"
			);
			attachmentData = initializer.get();
		}
		return deserializeAttachment(attachmentData, nbtCompound, deserializer);
	}

	public static boolean hasPersistentAttachments(@Nullable IdentityHashMap<AttachmentType<?>, ?> map) {
		if (map == null) {
			return false;
		}

		for (AttachmentType<?> type : map.keySet()) {
			if (type.isPersistent()) {
				return true;
			}
		}

		return false;
	}

	public static NbtCompound serializeAttachment(Object attachmentData, BiConsumer<Object, WriteView> serializer) {
		try (ErrorReporter.Logging reporter = new ErrorReporter.Logging(LOGGER)) {
			NbtWriteView writeView = NbtWriteView.create(reporter);
			serializer.accept(attachmentData, writeView);
			return writeView.getNbt();
		}
	}

	public static <A> A deserializeAttachment(A attachmentData, NbtCompound nbtCompound, BiConsumer<A, ReadView> deserializer) {
		try (ErrorReporter.Logging reporter = new ErrorReporter.Logging(LOGGER)) {
			ReadView readView = NbtReadView.create(reporter, DynamicRegistryManager.EMPTY, nbtCompound);
			deserializer.accept(attachmentData, readView);
		}
		return attachmentData;
	}
}
