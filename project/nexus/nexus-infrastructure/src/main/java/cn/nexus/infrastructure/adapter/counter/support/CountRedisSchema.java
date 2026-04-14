package cn.nexus.infrastructure.adapter.counter.support;

import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.domain.counter.model.valobj.UserCounterType;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Code-defined Count Redis slot schema for object and user counters.
 */
public final class CountRedisSchema {

    public static final int SLOT_WIDTH_BYTES = 5;

    private static final CountRedisSchema POST = new CountRedisSchema(
            "post_counter",
            Map.of(ObjectCounterType.LIKE, 0),
            Map.of());

    private static final CountRedisSchema COMMENT = new CountRedisSchema(
            "comment_counter",
            orderedObjectSlots(
                    ObjectCounterType.LIKE, 0,
                    ObjectCounterType.REPLY, 1),
            Map.of());

    private static final CountRedisSchema USER = new CountRedisSchema(
            "user_counter",
            Map.of(),
            orderedUserSlots(
                    UserCounterType.FOLLOWING, 0,
                    UserCounterType.FOLLOWER, 1,
                    UserCounterType.POST, 2,
                    UserCounterType.LIKE_RECEIVED, 3,
                    UserCounterType.FAVORITE_RECEIVED, 4));

    private final String schemaName;
    private final Map<ObjectCounterType, Integer> objectSlots;
    private final Map<UserCounterType, Integer> userSlots;
    private final String[] orderedFieldNames;

    private CountRedisSchema(String schemaName,
                             Map<ObjectCounterType, Integer> objectSlots,
                             Map<UserCounterType, Integer> userSlots) {
        this.schemaName = schemaName;
        this.objectSlots = Collections.unmodifiableMap(objectSlots);
        this.userSlots = Collections.unmodifiableMap(userSlots);
        this.orderedFieldNames = initOrderedFieldNames(objectSlots, userSlots);
    }

    public static CountRedisSchema forObject(ReactionTargetTypeEnumVO targetType) {
        if (targetType == null) {
            return null;
        }
        return switch (targetType) {
            case POST -> POST;
            case COMMENT -> COMMENT;
            case USER -> null;
        };
    }

    public static CountRedisSchema user() {
        return USER;
    }

    public static Map<String, Long> userSnapshotDefaults() {
        Map<String, Long> defaults = new HashMap<>(USER.slotCount());
        for (String fieldName : USER.orderedFieldNames()) {
            defaults.put(fieldName, 0L);
        }
        return defaults;
    }

    public String schemaName() {
        return schemaName;
    }

    public Map<ObjectCounterType, Integer> objectSlots() {
        return objectSlots;
    }

    public Map<UserCounterType, Integer> userSlots() {
        return userSlots;
    }

    public int slotOf(ObjectCounterType counterType) {
        return objectSlots.getOrDefault(counterType, -1);
    }

    public int slotOf(UserCounterType counterType) {
        return userSlots.getOrDefault(counterType, -1);
    }

    public int slotCount() {
        return orderedFieldNames.length;
    }

    public int totalPayloadBytes() {
        return slotCount() * SLOT_WIDTH_BYTES;
    }

    public String fieldNameAt(int slot) {
        if (slot < 0 || slot >= orderedFieldNames.length) {
            return null;
        }
        return orderedFieldNames[slot];
    }

    public String[] orderedFieldNames() {
        return Arrays.copyOf(orderedFieldNames, orderedFieldNames.length);
    }

    private static Map<ObjectCounterType, Integer> orderedObjectSlots(Object... rawPairs) {
        EnumMap<ObjectCounterType, Integer> slots = new EnumMap<>(ObjectCounterType.class);
        for (int i = 0; i < rawPairs.length; i += 2) {
            slots.put((ObjectCounterType) rawPairs[i], (Integer) rawPairs[i + 1]);
        }
        return slots;
    }

    private static Map<UserCounterType, Integer> orderedUserSlots(Object... rawPairs) {
        EnumMap<UserCounterType, Integer> slots = new EnumMap<>(UserCounterType.class);
        for (int i = 0; i < rawPairs.length; i += 2) {
            slots.put((UserCounterType) rawPairs[i], (Integer) rawPairs[i + 1]);
        }
        return slots;
    }

    private static String[] initOrderedFieldNames(Map<ObjectCounterType, Integer> objectSlots,
                                                  Map<UserCounterType, Integer> userSlots) {
        Map<Integer, String> ordered = new LinkedHashMap<>();
        objectSlots.forEach((type, slot) -> ordered.put(slot, type.getCode()));
        userSlots.forEach((type, slot) -> ordered.put(slot, type.getCode()));
        String[] names = new String[ordered.size()];
        ordered.forEach((slot, name) -> names[slot] = name);
        return names;
    }
}
