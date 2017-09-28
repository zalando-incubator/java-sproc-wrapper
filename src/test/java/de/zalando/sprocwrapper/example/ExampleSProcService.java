package de.zalando.sprocwrapper.example;

import java.util.Date;
import java.util.List;
import java.util.Set;

import org.joda.time.DateTime;

import de.zalando.sprocwrapper.SProcCall;
import de.zalando.sprocwrapper.SProcCall.Validate;
import de.zalando.sprocwrapper.SProcParam;
import de.zalando.sprocwrapper.SProcService;
import de.zalando.sprocwrapper.sharding.ShardKey;

/**
 * @author  jmussler
 */
@SProcService
public interface ExampleSProcService {
    @SProcCall(name = "create_article_simple")
    void createArticleSimple(@SProcParam String sku);

    @SProcCall
    void createArticleSimples(@SProcParam List<String> skus);

    @SProcCall(name = "create_article_simple_items")
    String createArticleSimpleItems(@SProcParam(name = "sku")
            @ShardKey String sku, @SProcParam int stockId,
            @SProcParam(name = "quantity") int quantity,
            @SProcParam(name = "price") int purchasePrice,
            @SProcParam(name = "referencenumber") String referenceNumber);

    @SProcCall
    Integer getSimpleInt();

    @SProcCall(name = "get_simple_int")
    int getSimpleIntAsPrimitive();

    @SProcCall
    long getSimpleLong();

    @SProcCall
    int getSimpleInt(@SProcParam int i);

    @SProcCall
    boolean getBoolean();

    @SProcCall
    void setBoolean(@SProcParam boolean bool);

    @SProcCall
    void useEnumParam(@SProcParam ExampleEnum enumParameter);

    @SProcCall
    void useDateParam(@SProcParam Date d);

    @SProcCall
    void useDateParam2(@SProcParam(sqlType = java.sql.Types.DATE) Date d);

    @SProcCall
    void useCharParam(@SProcParam char c);

    @SProcCall
    void useIntegerListParam(@SProcParam List<Integer> l);

    @SProcCall
    void getSimpleIntVoid(@SProcParam int i);

    @SProcCall
    boolean login(@SProcParam String userName,
            @SProcParam(sensitive = true) String password);

    @SProcCall(sql = "SELECT 'a' AS a, 'b' AS b UNION ALL SELECT 'c', 'd'")
    List<ExampleDomainObject> getResult();

    @SProcCall(sql = "SELECT 'a' AS a, 'b' AS b")
    ExampleDomainObject getSingleResult();

    @SProcCall(sql = "SELECT 5555")
    Integer getBla();

    @SProcCall(sql = "SELECT '2012-02-03 12:00:21'::timestamp")
    Date getFixedTestDate();

    @SProcCall(sql = "SELECT 1 UNION ALL SELECT 2")
    List<Integer> getInts();

    @SProcCall(sql = "SELECT 1000 UNION ALL SELECT 2002")
    List<Long> getLongs();

    @SProcCall
    String createOrUpdateObject(@SProcParam ExampleDomainObject object);

    @SProcCall
    String createOrUpdateObjectWithRandomFields(@SProcParam ExampleDomainObjectWithRandomFields object);

    @SProcCall
    String createOrUpdateObjectWithEnum(@SProcParam ExampleDomainObjectWithEnum object);

    @SProcCall
    String createOrUpdateObjectWithDate(@SProcParam ExampleDomainObjectWithDate object);

    @SProcCall
    String createOrUpdateMultipleObjects(
            @SProcParam(type = "example_domain_object[]") List<ExampleDomainObject> objects);

    @SProcCall
    String createOrUpdateMultipleObjectsWithRandomFields(
            @SProcParam(type = "example_domain_object_with_random_fields[]") List<ExampleDomainObjectWithRandomFields> object);

    @SProcCall(name = "create_or_update_multiple_objects_with_random_fields")
    String createOrUpdateMultipleObjectsWithRandomFieldsNoAnnotation(
            @SProcParam List<ExampleDomainObjectWithRandomFields> object);

    @SProcCall(name = "create_or_update_multiple_objects_with_random_fields")
    String createOrUpdateMultipleObjectsWithRandomFieldsNoAnnotationOverride(
            @SProcParam List<ExampleDomainObjectWithRandomFieldsOverride> object);

    @SProcCall
    String createOrUpdateMultipleObjectsWithMap(
            @SProcParam(type = "example_domain_object_with_map[]") List<ExampleDomainObjectWithMap> objects);

    @SProcCall
    String createOrUpdateMultipleObjectsWithInnerObject(
            @SProcParam(type = "example_domain_object_with_inner_object[]") List<ExampleDomainObjectWithInnerObject> objects);

    @SProcCall
    void createOrUpdateMultipleObjectsWithMapVoid(
            @SProcParam(type = "example_domain_object_with_map[]") List<ExampleDomainObjectWithMap> objects);

    @SProcCall
    boolean reserveStock(@ShardKey @SProcParam String sku);

    @SProcCall(name = "create_or_update_address")
    AddressPojo createAddress(@SProcParam AddressPojo a);

    @SProcCall(name = "get_address")
    AddressPojo getAddress(@SProcParam AddressPojo a);

    @SProcCall(name = "get_address_sql")
    AddressPojo getAddressSql(@SProcParam AddressPojo a);

    @SProcCall(sql = "SELECT pg_sleep( ? )", timeoutInMilliSeconds = 3 * 1000)
    void testTimeoutSetTo3s(@SProcParam int sleep);

    @SProcCall(sql = "SELECT pg_sleep( ? )", timeoutInMilliSeconds = 5 * 1000)
    void testTimeoutSetTo5s(@SProcParam int sleep);

    @SProcCall(sql = "SHOW statement_timeout")
    String showTimeout();

    @SProcCall(sql = "SELECT 'a','b',null")
    ExampleDomainObjectWithInnerObject getObjectWithNull();

    @SProcCall(sql = "SELECT '2013-04-05 11:12:13.123456'::timestamp")
    java.sql.Timestamp getMicorsecondTimestamp();

    @SProcCall
    ExampleDomainObjectWithSimpleTransformer testSimpleTransformer(
            @SProcParam ExampleDomainObjectWithSimpleTransformer exampleDomainObjectWithSimpleTransformer);

    @SProcCall
    ExampleDomainObjectWithEnum getEntityWithEnum(@SProcParam long id);

    @SProcCall
    ExampleDomainObjectWithGlobalTransformer testGlobalTransformer(
            @SProcParam ExampleDomainObjectWithGlobalTransformer exampleDomainObjectWithGlobalTransformer);

    @SProcCall
    GlobalTransformedObject testGlobalTransformer2(@SProcParam GlobalTransformedObject globalTransformedObject);

    @SProcCall
    GlobalTransformedObject testGlobalTransformer3(@SProcParam GlobalTransformedObject globalTransformedObject,
            @SProcParam ExampleDomainObject object);

    @SProcCall
    List<GlobalTransformedObject> testGlobalTransformer4(
            @SProcParam List<GlobalTransformedObject> globalTransformedObjects, @SProcParam ExampleDomainObject object);

    @SProcCall
    List<GlobalTransformedObject> testGlobalTransformer5(
            @SProcParam Set<GlobalTransformedObject> globalTransformedObjects, @SProcParam ExampleDomainObject object);

    @SProcCall
    DateTime testGlobalTransformer6(@SProcParam DateTime dateTime);

    @SProcCall(validate = Validate.AS_DEFINED_IN_SERVICE)
    ExampleDomainObjectWithValidation testSprocCallWithoutValidation1(
            @SProcParam ExampleDomainObjectWithValidation exampleDomainObjectWithValidation);

    @SProcCall(validate = Validate.NO)
    ExampleDomainObjectWithValidation testSprocCallWithoutValidation2(
            @SProcParam ExampleDomainObjectWithValidation exampleDomainObjectWithValidation);

    @SProcCall(validate = Validate.YES)
    ExampleDomainObjectWithValidation testSprocCallWithValidation(
            @SProcParam ExampleDomainObjectWithValidation exampleDomainObjectWithValidation);

    @SProcCall(validate = Validate.NO)
    ExampleDomainObjectWithValidation testSprocCallWithValidationInvalidRet1(
            @SProcParam ExampleDomainObjectWithValidation exampleDomainObjectWithValidation);

    @SProcCall(validate = Validate.YES)
    ExampleDomainObjectWithValidation testSprocCallWithValidationInvalidRet2(
            @SProcParam ExampleDomainObjectWithValidation exampleDomainObjectWithValidation);

    @SProcCall
    ExampleDomainObjectWithEmbed getEntityWithEmbed();

    @SProcCall
    ExampleDomainObjectWithEmbed getEntityWithEmbedEmptyString();

    @SProcCall
    ExampleDomainObjectWithEmbed getEntityWithEmbedNullFields();

    @SProcCall
    ExampleDomainObjectWithoutSetters getEntityWithoutSetters();

    @SProcCall
    ExampleDomainObjectWithInnerObject getEntityWithNullInnerObject();

    @SProcCall
    Example1DomainObject1 getExample1EntityWithNumbers1(@SProcParam Example1DomainObject1 domain);

    @SProcCall
    Example2DomainObject1 getExample2EntityWithNumbers1();

    @SProcCall
    List<ExampleDomainObject> getListComplexObjects();

    @SProcCall
    Order getOrders(@SProcParam int id);

    @SProcCall
    int createOrder(@SProcParam String orderNumber, @SProcParam OrderMonetaryAmount amount);

    @SProcCall
    int createOrder(@SProcParam String orderNumber, @SProcParam OrderMonetaryAmount amount,
            @SProcParam AddressPojo address);

    @SProcCall
    int createOrder(@SProcParam Order order);

    @SProcCall(sql = "SELECT 'ENUM_CONST_2'::example_enum;")
    ExampleEnum getExampleEnum();

    @SProcCall(sql = "SELECT NULL::example_enum;")
    ExampleEnum getNullExampleEnum();

    @SProcCall(sql = "SELECT 1 as a, 2 as b")
    LookupType getValueForTypeLookup();

    @SProcCall(sql = "SELECT 1 as a, 2 as b")
    List<LookupType> getValueForTypeLookupList();

    @SProcCall(
        sql =
            "SELECT 3 AS count, ARRAY[ROW(4)]::ztest_schema1.lookup_type_schema[] AS schema_1, ARRAY[(1,2)]::ztest_schema2.lookup_type_schema[] AS schema_2"
    )
    WrapperLookupSchema getValueForTypeLookupSchema();

    @SProcCall(sql = "SELECT 4 AS count, ARRAY[ROW(5)]::ztest_schema1.lookup_type_schema[] AS list")
    WrapperOptionalLookupType getOptionalLookupTypeWithoutMapping();

    @SProcCall(sql = "SELECT 4 AS count, ARRAY[ROW(5)]::ztest_schema1.lookup_type_schema[] AS list")
    WrapperGuavaOptionalLookupType getGuavaOptionalLookupTypeWithoutMapping();

    @SProcCall
    int testInheritanceFunction(@SProcParam TestInheritanceChild c);

    @SProcCall
    List<LookupType> testDatabaseTypeWithoutName(@SProcParam List<LookupType> lookupType);

    @SProcCall
    List<ExampleDomainObjectWithInnerObject> getEmptyList(@SProcParam List<ExampleDomainObjectWithInnerObject> list);

    @SProcCall(sql = "SELECT bt_value FROM ztest_schema1.basic_table WHERE bt_key = ?")
    String getValueFromBasicTable(@SProcParam String key);

    @SProcCall(sql = "UPDATE ztest_schema1.basic_table SET bt_value = ? WHERE bt_key = ? RETURNING bt_id")
    List<Integer> changeBasicTable(@SProcParam String value, @SProcParam String key);

    @SProcCall(sql = "SELECT version()")
    String getPostgreSqlVersion();

    @SProcCall
    PartialObject getPartialObject(@SProcParam PartialObject partialObject);

    @SProcCall
    PartialObject getPartialObject(@SProcParam NotPartialObject partialObject);

}
