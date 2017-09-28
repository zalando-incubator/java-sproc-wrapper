package de.zalando.sprocwrapper.example;

import de.zalando.typemapper.annotations.DatabaseField;

import java.util.List;

public class WrapperGuavaOptionalLookupType {

    @DatabaseField
    public int count;

    @DatabaseField
    public List<GuavaOptionalLookupType> list;
}
