/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cxf.jaxrs.ext.search.client;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.Duration;

import static junit.framework.Assert.assertEquals;

import org.apache.cxf.jaxrs.ext.search.SearchUtils;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class FiqlSearchConditionBuilderTest {
    private static FiqlSearchConditionBuilder b = new FiqlSearchConditionBuilder();
    private static TimeZone tz;
    private DateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm Z");
    
    @BeforeClass
    public static void beforeClass() {
        tz = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("GMT"));
    }
    
    @AfterClass
    public static void afterClass() {
        // restoring defaults
        TimeZone.setDefault(tz);
    }
    
    
    @Test
    public void testEmptyBuild() {
        assertEquals("", b.query());
    }

    @Test
    public void testEqualToString() {
        String ret = b.is("foo").equalTo("literalOrPattern*").query();
        assertEquals("foo==literalOrPattern*", ret);
    }

    @Test
    public void testEqualToNumber() {
        String ret = b.is("foo").equalTo(123.5).query();
        assertEquals("foo==123.5", ret);
    }

    @Test
    public void testEqualToDate() throws ParseException {
        Date d = df.parse("2011-03-01 12:34 +0000");
        String ret = b.is("foo").equalTo(d).query();
        assertEquals("foo==2011-03-01T12:34:00.000+00:00", ret);
    }
    
    @Test
    public void testEqualToDateWithCustomFormat() throws ParseException {
        
        Map<String, String> props = new HashMap<String, String>();
        props.put(SearchUtils.DATE_FORMAT_PROPERTY, "yyyy-MM-dd'T'HH:mm:ss");
        props.put(SearchUtils.TIMEZONE_SUPPORT_PROPERTY, "false");
        
        Date d = df.parse("2011-03-01 12:34 +0000");
        
        FiqlSearchConditionBuilder bCustom = new FiqlSearchConditionBuilder(props);
        
        String ret = bCustom.is("foo").equalTo(d).query();
        assertEquals("foo==2011-03-01T12:34:00", ret);
    }

    @Test
    public void testEqualToDuration() throws ParseException, DatatypeConfigurationException {
        Duration d = DatatypeFactory.newInstance().newDuration(false, 0, 0, 1, 12, 0, 0); 
        String ret = b.is("foo").equalTo(d).query();
        assertEquals("foo==-P0Y0M1DT12H0M0S", ret);
    }

    @Test
    public void testNotEqualToString() {
        String ret = b.is("foo").notEqualTo("literalOrPattern*").query();
        assertEquals("foo!=literalOrPattern*", ret);
    }

    @Test
    public void testNotEqualToNumber() {
        String ret = b.is("foo").notEqualTo(123.5).query();
        assertEquals("foo!=123.5", ret);
    }

    @Test
    public void testNotEqualToDate() throws ParseException {
        Date d = df.parse("2011-03-01 12:34 +0000");
        String ret = b.is("foo").notEqualTo(d).query();
        assertEquals("foo!=2011-03-01T12:34:00.000+00:00", ret);
    }

    @Test
    public void testNotEqualToDuration() throws ParseException, DatatypeConfigurationException {
        Duration d = DatatypeFactory.newInstance().newDuration(false, 0, 0, 1, 12, 0, 0); 
        String ret = b.is("foo").notEqualTo(d).query();
        assertEquals("foo!=-P0Y0M1DT12H0M0S", ret);
    }

    @Test
    public void testGreaterThanString() {
        String ret = b.is("foo").lexicalAfter("abc").query();
        assertEquals("foo=gt=abc", ret);
    }

    @Test
    public void testLessThanString() {
        String ret = b.is("foo").lexicalBefore("abc").query();
        assertEquals("foo=lt=abc", ret);
    }

    @Test
    public void testLessOrEqualToString() {
        String ret = b.is("foo").lexicalNotAfter("abc").query();
        assertEquals("foo=le=abc", ret);
    }

    @Test
    public void testGreaterOrEqualToString() {
        String ret = b.is("foo").lexicalNotBefore("abc").query();
        assertEquals("foo=ge=abc", ret);
    }
    
    @Test
    public void testGreaterThanNumber() {
        String ret = b.is("foo").greaterThan(25).query();
        assertEquals("foo=gt=25.0", ret);
    }

    @Test
    public void testLessThanNumber() {
        String ret = b.is("foo").lessThan(25.333).query();
        assertEquals("foo=lt=25.333", ret);
    }

    @Test
    public void testLessOrEqualToNumber() {
        String ret = b.is("foo").lessOrEqualTo(0).query();
        assertEquals("foo=le=0.0", ret);
    }

    @Test
    public void testGreaterOrEqualToNumber() {
        String ret = b.is("foo").greaterOrEqualTo(-5).query();
        assertEquals("foo=ge=-5.0", ret);
    }

    @Test
    public void testGreaterThanDate() throws ParseException {
        Date d = df.parse("2011-03-02 22:33 +0000");
        String ret = b.is("foo").after(d).query();
        assertEquals("foo=gt=2011-03-02T22:33:00.000+00:00", ret);
    }

    @Test
    public void testLessThanDate() throws ParseException {
        Date d = df.parse("2011-03-02 22:33 +0000");
        String ret = b.is("foo").before(d).query();
        assertEquals("foo=lt=2011-03-02T22:33:00.000+00:00", ret);
    }

    @Test
    public void testLessOrEqualToDate() throws ParseException {
        Date d = df.parse("2011-03-02 22:33 +0000");
        String ret = b.is("foo").notAfter(d).query();
        assertEquals("foo=le=2011-03-02T22:33:00.000+00:00", ret);
    }

    @Test
    public void testGreaterOrEqualToDate() throws ParseException {
        Date d = df.parse("2011-03-02 22:33 +0000");
        String ret = b.is("foo").notBefore(d).query();
        assertEquals("foo=ge=2011-03-02T22:33:00.000+00:00", ret);
    }

    @Test
    public void testGreaterThanDuration() throws DatatypeConfigurationException {
        Duration d = DatatypeFactory.newInstance().newDuration(false, 0, 0, 1, 12, 0, 0); 
        String ret = b.is("foo").after(d).query();
        assertEquals("foo=gt=-P0Y0M1DT12H0M0S", ret);
    }

    @Test
    public void testLessThanDuration() throws DatatypeConfigurationException {
        Duration d = DatatypeFactory.newInstance().newDuration(false, 0, 0, 1, 12, 0, 0); 
        String ret = b.is("foo").before(d).query();
        assertEquals("foo=lt=-P0Y0M1DT12H0M0S", ret);
    }

    @Test
    public void testLessOrEqualToDuration() throws DatatypeConfigurationException {
        Duration d = DatatypeFactory.newInstance().newDuration(false, 0, 0, 1, 12, 0, 0); 
        String ret = b.is("foo").notAfter(d).query();
        assertEquals("foo=le=-P0Y0M1DT12H0M0S", ret);
    }

    @Test
    public void testGreaterOrEqualToDuration() throws DatatypeConfigurationException {
        Duration d = DatatypeFactory.newInstance().newDuration(false, 0, 0, 1, 12, 0, 0); 
        String ret = b.is("foo").notBefore(d).query();
        assertEquals("foo=ge=-P0Y0M1DT12H0M0S", ret);
    }
    
    @Test
    public void testOrSimple() {
        String ret = b.is("foo").greaterThan(20).or().is("foo").lessThan(10).query();
        assertEquals("foo=gt=20.0,foo=lt=10.0", ret);
    }    
    
    @Test
    public void testAndSimple() {
        String ret = b.is("foo").greaterThan(20).and().is("bar").equalTo("plonk").query();
        assertEquals("foo=gt=20.0;bar==plonk", ret);
    }
    
    @Test
    public void testOrComplex() {
        String ret = b.or(b.is("foo").equalTo("aaa"), b.is("bar").equalTo("bbb")).query();
        assertEquals("(foo==aaa,bar==bbb)", ret);
    }    

    @Test
    public void testAndComplex() {
        String ret = b.and(b.is("foo").equalTo("aaa"), b.is("bar").equalTo("bbb")).query();
        assertEquals("(foo==aaa;bar==bbb)", ret);
    }    

    @Test
    public void testComplex1() {
        String ret = b.is("foo").equalTo(123.4).or().and(
            b.is("bar").equalTo("asadf*"), 
            b.is("baz").lessThan(20)).query();
        assertEquals("foo==123.4,(bar==asadf*;baz=lt=20.0)", ret);
    }

    @Test
    public void testComplex2() {
        String ret = b.is("foo").equalTo(123.4).or().is("foo").equalTo("null").and().or(
            b.is("bar").equalTo("asadf*"), 
            b.is("baz").lessThan(20).and().or(
                b.is("sub1").equalTo(0),
                b.is("sub2").equalTo(0))).query();
        
        assertEquals("foo==123.4,foo==null;(bar==asadf*,baz=lt=20.0;(sub1==0.0,sub2==0.0))", ret);
    }
}