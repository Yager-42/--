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

    public static final String SCHEMA_ID = "v1";
    public static final int SCHEMA_LEN = 5;
    public static final int FIELD_SIZE = 4;
    public static final int SLOT_WIDTH_BYTES = FIELD_SIZE;

    private static final CountRedisSchema POST = new CountRedisSchema(
            SCHEMA_ID,
            orderedObjectSlots(
                    ObjectCounterType.LIKE, 1,
                    ObjectCounterType.FAV, 2),
            Map.of());

    private static final CountRedisSchema USER = new CountRedisSchema(
            SCHEMA_ID,
            Map.of(),
            orderedUserSlots(
                    UserCounterType.FOLLOWINGS, 1,
                    UserCounterType.FOLLOWERS, 2,
                    UserCounterType.POSTS, 3,
                    UserCounterType.LIKES_RECEIVED, 4,
                    UserCounterType.FAVS_RECEIVED, 5));

    private final String schemaName;
    private final Map<ObjectCounterType, Integer> objectSlots;
    private final Map<UserCounterType, Integer> userLogicalIndexes;
    private final String[] orderedFieldNames;

    private CountRedisSchema(String schemaName,
                             Map<ObjectCounterType, Integer> objectSlots,
                             Map<UserCounterType, Integer> userLogicalIndexes) {
        this.schemaName = schemaName;
        this.objectSlots = Collections.unmodifiableMap(objectSlots);
        this.userLogicalIndexes = Collections.unmodifiableMap(userLogicalIndexes);
        this.orderedFieldNames = initOrderedFieldNames(objectSlots, userLogicalIndexes);
    }

    public static CountRedisSchema forObject(ReactionTargetTypeEnumVO targetType) {
        if (targetType == null) {
            return null;
        }
        return switch (targetType) {
            case POST -> POST;
            case COMMENT -> null;
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
        EnumMap<UserCounterType, Integer> offsets = new EnumMap<>(UserCounterType.class);
        userLogicalIndexes.forEach((type, idx) -> offsets.put(type, idx - 1));
        return offsets;
    }

    public int slotOf(ObjectCounterType counterType) {
        return objectSlots.getOrDefault(counterType, -1);
    }

    public int slotOf(UserCounterType counterType) {
        int idx = logicalIndexOf(counterType);
        return idx < 1 ? -1 : idx - 1;
    }

    public int logicalIndexOf(UserCounterType counterType) {
        return userLogicalIndexes.getOrDefault(counterType, -1);
    }

    public int byteOffsetOf(UserCounterType counterType) {
        int idx = logicalIndexOf(counterType);
        return idx < 1 ? -1 : (idx - 1) * FIELD_SIZE;
    }

    public int slotCount() {
        return SCHEMA_LEN;
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
                                                  Map<UserCounterType, Integer> userLogicalIndexes) {
        String[] names = new String[SCHEMA_LEN];
        if (!objectSlots.isEmpty()) {
            names[0] = "read";
            names[1] = "like";
            names[2] = "fav";
            names[3] = "comment";
            names[4] = "repost";
            return names;
        }
        userLogicalIndexes.forEach((type, idx) -> {
            if (idx >= 1 && idx <= SCHEMA_LEN) {
                names[idx - 1] = type.getCode();
            }
        });
        for (int i = 0; i < names.length; i++) {
            if (names[i] == null) {
                names[i] = "reserved_" + (i + 1);
            }
        }
        return names;
    }
}
