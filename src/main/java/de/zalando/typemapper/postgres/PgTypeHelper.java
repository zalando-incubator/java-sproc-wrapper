package de.zalando.typemapper.postgres;

import java.lang.reflect.Array;
import java.lang.reflect.Field;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;

import java.util.*;

import javax.persistence.Column;

import org.postgresql.core.BaseConnection;

import org.postgresql.jdbc.PostgresJDBCDriverReusedTimestampUtils;

import org.postgresql.util.PGobject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.zalando.sprocwrapper.util.NameUtils;

import de.zalando.typemapper.annotations.DatabaseField;
import de.zalando.typemapper.annotations.DatabaseType;
import de.zalando.typemapper.core.DatabaseFieldDescriptor;
import de.zalando.typemapper.core.Mapping;
import de.zalando.typemapper.core.OptionalMapper;
import de.zalando.typemapper.core.ValueTransformer;
import de.zalando.typemapper.core.db.DbType;
import de.zalando.typemapper.core.db.DbTypeField;
import de.zalando.typemapper.core.db.DbTypeRegister;
import de.zalando.typemapper.core.fieldMapper.AnyTransformer;
import de.zalando.typemapper.core.fieldMapper.DefaultObjectMapper;
import de.zalando.typemapper.core.fieldMapper.GlobalValueTransformerRegistry;
import de.zalando.typemapper.core.fieldMapper.ObjectMapper;

public class PgTypeHelper {

    private static final Map<String, Integer> pgGenericTypeNameToSQLTypeMap;
    private static final Map<Field, DatabaseFieldDescriptor> fieldToDataBaseFieldDescriptorMap = Collections
            .synchronizedMap(new HashMap<Field, DatabaseFieldDescriptor>());

    private static final PostgresJDBCDriverReusedTimestampUtils postgresJDBCDriverReusedTimestampUtils =
        new PostgresJDBCDriverReusedTimestampUtils();

    private static final Logger LOG = LoggerFactory.getLogger(PgTypeHelper.class);

    static {
        final Map<String, Integer> m = new HashMap<String, Integer>();
        m.put("int2", Types.SMALLINT);
        m.put("int4", Types.INTEGER);
        m.put("oid", Types.BIGINT);
        m.put("int8", Types.BIGINT);
        m.put("money", Types.DOUBLE);
        m.put("numeric", Types.NUMERIC);
        m.put("float4", Types.REAL);
        m.put("float8", Types.DOUBLE);
        m.put("bpchar", Types.CHAR);
        m.put("varchar", Types.VARCHAR);
        m.put("text", Types.VARCHAR);
        m.put("name", Types.VARCHAR);
        m.put("bool", Types.BOOLEAN);
        m.put("bit", Types.BIT);
        m.put("date", Types.DATE);
        m.put("time", Types.TIME);
        m.put("timetz", Types.TIME);
        m.put("timestamp", Types.TIMESTAMP);
        m.put("timestamptz", Types.TIMESTAMP);
        pgGenericTypeNameToSQLTypeMap = Collections.unmodifiableMap(m);
    }

    private static final Map<String, String> pgGenericTypeNameAliasMap;

    static {
        final Map<String, String> m = new HashMap<String, String>();
        m.put("smallint", "int2");
        m.put("integer", "int4");
        m.put("int", "int4");
        m.put("bigint", "int8");
        m.put("real", "float4");
        m.put("float", "float8");
        m.put("double precision", "float8");
        m.put("boolean", "bool");
        m.put("decimal", "numeric");
        m.put("character varying", "varchar");
        m.put("char", "bpchar");
        m.put("character", "bpchar");
        pgGenericTypeNameAliasMap = Collections.unmodifiableMap(m);
    }

    /**
     * A simple method to get an appropriate java.sql.types.* value for the given PostgreSQL type name
     *
     * @param   typeName
     *
     * @return  SQL type
     */
    public static final int getSQLType(final String typeName) {
        String trimmedTypeName = typeName.trim().toLowerCase(Locale.US);
        final Integer n = pgGenericTypeNameToSQLTypeMap.get(trimmedTypeName);
        if (n == null) {

            // look up the alias
            trimmedTypeName = pgGenericTypeNameAliasMap.get(trimmedTypeName);
            if (trimmedTypeName != null) {
                return pgGenericTypeNameToSQLTypeMap.get(trimmedTypeName);
            }

            return Types.OTHER;
        } else {
            return n;
        }
    }

    private static final Map<Class<?>, String> javaGenericClassToPgTypeNameMap;

    static {
        final Map<Class<?>, String> m = new HashMap<Class<?>, String>();
        m.put(short.class, "int2");
        m.put(Short.class, "int2");
        m.put(int.class, "int4");
        m.put(Integer.class, "int4");
        m.put(long.class, "int8");
        m.put(Long.class, "int8");
        m.put(float.class, "float4");
        m.put(Float.class, "float4");
        m.put(double.class, "float8");
        m.put(Double.class, "float8");
        m.put(char.class, "bpchar");
        m.put(Character.class, "bpchar");
        m.put(String.class, "text");
        m.put(boolean.class, "bool");
        m.put(Boolean.class, "bool");
        m.put(Date.class, "timestamp");
        m.put(java.sql.Date.class, "timestamp");
        m.put(java.sql.Timestamp.class, "timestamp");
        m.put(java.sql.Time.class, "timestamp");
        m.put(java.util.UUID.class, "uuid");
        javaGenericClassToPgTypeNameMap = Collections.unmodifiableMap(m);
    }

    public static String getSQLNameForClass(final Class<?> elementClass) {
        if (elementClass == null) {
            return null;
        }

        final String typeName = javaGenericClassToPgTypeNameMap.get(elementClass);
        return typeName;
    }

    public static final class PgTypeDataHolder {
        private final String typeName;
        private final Collection<Object> attributes;

        public PgTypeDataHolder(final String typeName, final Collection<Object> attributes) {
            this.typeName = typeName;
            this.attributes = attributes;
        }

        public String getTypeName() {
            return typeName;
        }

        public Collection<Object> getAttributes() {
            return attributes;
        }

    }

    public static PgTypeDataHolder getObjectAttributesForPgSerialization(final Object obj, final String typeHint) {
        return getObjectAttributesForPgSerialization(obj, typeHint, null);
    }

    public static PgTypeDataHolder getObjectAttributesForPgSerialization(final Object obj, final String typeHint,
            final Connection connection) {
        return getObjectAttributesForPgSerialization(obj, typeHint, connection, false);
    }

    private static boolean isCglibProxy(final Object obj) {
        try {
            return obj.getClass().getDeclaredField("CGLIB$CALLBACK_0") != null;
        } catch (NoSuchFieldException e) {
            return false;
        }
    }

    private static Class<?> getActualClass(final Object obj) {
        if (isCglibProxy(obj)) {
            return obj.getClass().getSuperclass();
        } else {
            return obj.getClass();
        }
    }

    public static PgTypeDataHolder getObjectAttributesForPgSerialization(final Object obj, final String typeHint,
            final Connection connection, final boolean forceTypeHint) {
        if (obj == null) {
            throw new NullPointerException();
        }

        String typeName = null;

        final Class<?> clazz = getActualClass(obj);
        if (clazz.isPrimitive() || clazz.isArray()) {
            throw new IllegalArgumentException("Passed object should be a class with parameters");
        }

        // mapper defined
        if (clazz.equals(PgTypeDataHolder.class)) {
            return (PgTypeDataHolder) obj;
        }

        final DatabaseType databaseType = clazz.getAnnotation(DatabaseType.class);
        if (databaseType != null) {
            typeName = databaseType.name();
        }

        if (typeName == null || typeName.isEmpty() || forceTypeHint) {

            // if no annotation is given use the type hint parameter
            // use type hint if forceTypeHint is set to true
            typeName = typeHint;
        }

        if (typeName == null || typeName.isEmpty()) {

            // fill the name with de-CamelCased name if we could not get it from the annotation
            typeName = NameUtils.camelCaseToUnderscore(clazz.getSimpleName());
        }

        List<Object> resultList = null;
        TreeMap<Integer, Object> resultPositionMap = null;

        final Field[] fields = getFields(clazz);
        Map<String, DbTypeField> dbFields = null;

        if (connection != null) {
            try {
                final DbType dbType = DbTypeRegister.getDbType(typeName, connection);
                dbFields = new HashMap<String, DbTypeField>();
                for (final DbTypeField dbfield : dbType.getFields()) {
                    dbFields.put(dbfield.getName(), dbfield);
                }
            } catch (final Exception e) {
                throw new IllegalArgumentException("Could not get PG type information for " + typeName, e);
            }
        } else {

            // Hacky: sort fields alphabetically as class fields' order is undefined
            // http://stackoverflow.com/questions/1097807/java-reflection-is-the-order-of-class-fields-and-methods-standardized
            Arrays.sort(fields, new Comparator<Field>() {

                    @Override
                    public int compare(final Field a, final Field b) {
                        return a.getName().compareTo(b.getName());
                    }

                });
        }

        for (final Field f : fields) {
            final DatabaseFieldDescriptor databaseFieldDescriptor = getDatabaseFieldDescriptor(f);
            if (databaseFieldDescriptor != null) {
                if (!f.isAccessible()) {
                    f.setAccessible(true);
                }

                Object value;
                try {
                    value = OptionalMapper.get(f.get(obj));
                } catch (final IllegalArgumentException e) {
                    throw new IllegalArgumentException("Could not read value of field " + f.getName(), e);
                } catch (final IllegalAccessException e) {
                    throw new IllegalArgumentException("Could not read value of field " + f.getName(), e);
                }

                if (value != null) {

                    // here we need apply any value/type transformation before generating the
                    value = applyTransformer(f, databaseFieldDescriptor, value);
                }

                final int fieldPosition = databaseFieldDescriptor.getPosition();
                if (fieldPosition > 0) {
                    if (resultPositionMap == null) {
                        resultPositionMap = new TreeMap<Integer, Object>();
                    }

                    resultPositionMap.put(fieldPosition, value);

                } else {
                    DbTypeField dbField = null;
                    if (dbFields != null) {

                        if (resultPositionMap == null) {
                            resultPositionMap = new TreeMap<Integer, Object>();
                        }

                        // we have type information from database (field positions)
                        final String dbFieldName = Mapping.getDatabaseFieldName(f, databaseFieldDescriptor.getName());
                        dbField = dbFields.get(dbFieldName);

                        if (dbField == null) {
                            if (databaseType == null || !databaseType.partial()) {
                                throw new IllegalArgumentException("Field " + f.getName() + " (" + dbFieldName
                                        + ") of class " + clazz.getSimpleName()
                                        + " could not be found in database type " + typeName);
                            }
                        } else {
                            resultPositionMap.put(dbField.getPosition(), value);
                        }
                    }

                    if (dbField == null) {
                        if (resultList == null) {
                            resultList = new ArrayList<Object>();
                        }

                        if (databaseType == null || !databaseType.partial()) {
                            resultList.add(value);
                        }
                    }
                }
            }
        }

        final int fieldsWithDefinedPositions = resultPositionMap == null ? 0 : resultPositionMap.size();
        final int fieldsWithUndefinedPositions = resultList == null ? 0 : resultList.size();
        final int fieldsInDb = dbFields == null ? 0 : dbFields.size();

        if (fieldsInDb != fieldsWithDefinedPositions & connection != null) {
            LOG.error("fieldsInDb({})!=fieldsWithDefinedPositions({}) @DatabaseField annotation missing", fieldsInDb,
                fieldsWithDefinedPositions);
            throw new IllegalArgumentException("Class " + clazz.getName()
                    + " should have all its database related fields annotated");
        }

        if (fieldsWithDefinedPositions > 0 && fieldsWithUndefinedPositions > 0) {
            throw new IllegalArgumentException("Class " + clazz.getName()
                    + " should have all its database related fields marked with correct names or positions");
        }

        if (fieldsWithDefinedPositions > 0) {
            return new PgTypeDataHolder(typeName, Collections.unmodifiableCollection(resultPositionMap.values()));
        } else {
            return new PgTypeDataHolder(typeName, Collections.unmodifiableCollection(resultList));
        }
    }

    private static Field[] getFields(final Class<?> clazz) {
        DatabaseType databaseType = clazz.getAnnotation(DatabaseType.class);
        if (databaseType == null || !databaseType.inheritance()) {
            return clazz.getDeclaredFields();
        } else {
            List<Field> fields = new ArrayList<Field>();
            Class<?> targetClass = clazz;
            do {
                fields.addAll(Arrays.asList(targetClass.getDeclaredFields()));
                targetClass = targetClass.getSuperclass();
            } while (targetClass != null && targetClass != Object.class);

            return fields.toArray(new Field[fields.size()]);
        }
    }

    /**
     * get the database field annotation from a given field. This function will check against
     * {@link javax.persistence.Column} definition so that both annotations can be used to mark a DatabaseField.
     *
     * @param   field  the field to be checked
     *
     * @return  a {@link de.zalando.typemapper.core.DatabaseFieldDescriptor} based on a found {@link DatabaseField} or
     *          {@link javax.persistence.Column}
     */
    public static DatabaseFieldDescriptor getDatabaseFieldDescriptor(final Field field) {
        DatabaseFieldDescriptor databaseFieldDescriptor = fieldToDataBaseFieldDescriptorMap.get(field);
        if (databaseFieldDescriptor == null) {

            DatabaseField databaseField = field.getAnnotation(DatabaseField.class);
            if (databaseField != null) {
                databaseFieldDescriptor = new DatabaseFieldDescriptor(databaseField);
            } else {

                // do we have a column definition?
                final Column column = field.getAnnotation(Column.class);
                if (column != null) {
                    databaseFieldDescriptor = new DatabaseFieldDescriptor(column);
                }
            }

            fieldToDataBaseFieldDescriptorMap.put(field, databaseFieldDescriptor);
        }

        return databaseFieldDescriptor;
    }

    private static Object applyTransformer(final Field f, final DatabaseFieldDescriptor databaseFieldDescriptor,
            Object value) {

        // check if any transformer is explicitly defined:
        if (databaseFieldDescriptor.getTransformer() != null
                && !AnyTransformer.class.isAssignableFrom(databaseFieldDescriptor.getTransformer())) {
            try {
                @SuppressWarnings("unchecked")
                final ValueTransformer<Object, Object> transformer = (ValueTransformer<Object, Object>)
                    databaseFieldDescriptor.getTransformer().newInstance();

                // transform the value by the transformer into a database value:
                value = transformer.marshalToDb(value);
            } catch (final InstantiationException e) {
                throw new IllegalArgumentException("Could not instantiate transformer of field " + f.getName(), e);
            } catch (final IllegalAccessException e) {
                throw new IllegalArgumentException("Could not instantiate transformer of field " + f.getName(), e);
            }
        }

        Class<? extends ObjectMapper<?>> mapperClass = databaseFieldDescriptor.getMapper();
        if (mapperClass != null && mapperClass != DefaultObjectMapper.class) {
            try {
                @SuppressWarnings("unchecked")
                ObjectMapper<Object> mapper = (ObjectMapper<Object>) mapperClass.newInstance();

                value = mapper.marshalToDb(value);
            } catch (InstantiationException e) {
                throw new IllegalArgumentException("Could not instantiate mapper of field " + f.getName(), e);
            } catch (IllegalAccessException e) {
                throw new IllegalArgumentException("Could not instantiate mapper of field " + f.getName(), e);
            }
        }

        return value;
    }

    public static String toPgString(final Object o) {
        return toPgString(o, null);
    }

    /**
     * Serialize an object into a PostgreSQL string.
     *
     * @param  o  object to be serialized
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public static String toPgString(Object o, final Connection connection) {
        if (o == null) {
            return "NULL";
        }

        final StringBuilder sb = new StringBuilder();
        final Class<?> clazz = o.getClass();

        // check if there is a value transformer in the registry to transform this kind of object:
        final ValueTransformer valueTransformer = GlobalValueTransformerRegistry.getValueTransformerForClass(clazz);
        if (valueTransformer != null) {
            o = valueTransformer.marshalToDb(o);
            if (o == null || o.getClass() != clazz) {
                return toPgString(o, connection);
            }
        }

        if (clazz == Boolean.TYPE || clazz == Boolean.class) {
            sb.append(((Boolean) o) ? 't' : 'f');
        } else if (clazz.isPrimitive() || o instanceof Number) {
            sb.append(o);
        } else if (o instanceof PGobject || o instanceof java.sql.Array || o instanceof CharSequence
                || o instanceof Character || clazz == Character.TYPE) {
            sb.append(o.toString());
        } else if (clazz.isArray()) {
            final Class<?> componentClazz = clazz.getComponentType();
            if (componentClazz.isPrimitive()) {

                // we are fucked up again with the primitive arrays
                // cast it into a string list
                final int l = Array.getLength(o);
                final List<String> stringList = new ArrayList<String>(l);
                for (int i = 0; i < l; i++) {
                    stringList.add(String.valueOf(Array.get(o, i)));
                }

                sb.append(PgArray.ARRAY(stringList).toString());
            } else {
                sb.append(PgArray.ARRAY((Object[]) o).toString());
            }
        } else if (clazz.isEnum()) {
            sb.append(((Enum<?>) o).name());
        } else if (o instanceof Date) {
            Timestamp tmpd = null;
            if (o instanceof Timestamp) {
                tmpd = (Timestamp) o;
            } else {
                tmpd = new Timestamp(((Date) o).getTime());
            }

            if (connection instanceof BaseConnection) {

                // if we do have a valid postgresql connection use this one:
                final BaseConnection postgresBaseConnection = (BaseConnection) connection;
                sb.append(postgresBaseConnection.getTimestampUtils().toString(null, tmpd));
            } else {

                // no valid postgresql connection - use that one:
                sb.append(postgresJDBCDriverReusedTimestampUtils.toString(null, tmpd));
            }
        } else if (o instanceof Map) {
            final Map<?, ?> map = (Map<?, ?>) o;
            sb.append(HStore.serialize(map));
        } else if (o instanceof Collection) {
            sb.append(PgArray.ARRAY((Collection<?>) o).toString(connection));
        } else {

            // we do not know what to do with this object,
            // try to extract the attributes marked as @DatabaseField and pack it as a ROW
            // here we do not need to know the name of the PG type
            try {
                sb.append(asPGobject(o, null, connection).toString());
            } catch (final SQLException e) {
                throw new IllegalArgumentException("Could not serialize object of class " + clazz.getName(), e);
            }
        }

        return sb.toString();
    }

    public static PgRow asPGobject(final Object o) throws SQLException {
        return asPGobject(o, null, null);
    }

    public static PgRow asPGobject(final Object o, final String typeHint) throws SQLException {
        return asPGobject(o, typeHint, null);
    }

    public static PgRow asPGobject(final Object o, final String typeHint, final Connection connection)
        throws SQLException {
        return new PgRow(getObjectAttributesForPgSerialization(o, typeHint, connection), connection);
    }

    public static PgRow asPGobject(final Object o, final String typeHint, final Connection connection,
            final boolean forceTypeHint) throws SQLException {
        return new PgRow(getObjectAttributesForPgSerialization(o, typeHint, connection, forceTypeHint), connection);
    }
}
