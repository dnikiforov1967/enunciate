/**
 * Copyright © 2006-2016 Web Cohesion (info@webcohesion.com)
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
package com.webcohesion.enunciate.examples.cxf.tests;

import genealogy.client.cite.InfoSet;
import genealogy.client.cite.Source;
import genealogy.client.data.Event;
import genealogy.client.data.Person;
import genealogy.client.data.Relationship;
import genealogy.client.services.*;
import genealogy.client.services.impl.PersonServiceImpl;
import genealogy.client.services.impl.RelationshipServiceImpl;
import genealogy.client.services.impl.SourceServiceImpl;
import junit.framework.TestCase;

import javax.activation.DataHandler;
import javax.xml.ws.soap.MTOMFeature;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.*;

/**
 * @author Ryan Heaton
 */
public class TestFullCXFAPIWithJAXWSClient extends TestCase {

  /**
   * Tests the full API
   */
  public void testFullSOAPAPI() throws Exception {
    int port = 8080;
    if (System.getProperty("container.port") != null) {
      port = Integer.parseInt(System.getProperty("container.port"));
    }

    String context = "full";
    if (System.getProperty("container.test.context") != null) {
      context = System.getProperty("container.test.context");
    }

    SourceServiceImpl impl = new SourceServiceImpl("http://localhost:" + port + "/" + context + "/sources/source");
    SourceService sourceService = impl;
    Source source = sourceService.getSource("valid");
    assertEquals("valid", source.getId());
    assertEquals(URI.create("uri:some-uri"), source.getLink());
    assertEquals("some-title", source.getTitle());
    assertNull(sourceService.getSource("invalid"));

    try {
      sourceService.getSource("throw");
      fail("Should have thrown the exception.");
    }
    catch (ServiceException e) {
      assertEquals("some message", e.getMessage());
      assertEquals("another message", e.getAnotherMessage());
    }

    try {
      sourceService.getSource("unknown");
      fail("should have thrown the unknown source exception.");
    }
    catch (UnknownSourceException e) {
      UnknownSourceBean bean = e.getFaultInfo();
      assertEquals("unknown", bean.getSourceId());
      assertEquals(888, bean.getErrorCode());
    }

    assertEquals("newid", sourceService.addInfoSet("somesource", new InfoSet()));
    assertEquals("okay", sourceService.addInfoSet("othersource", new InfoSet()));
    assertEquals("resourceId", sourceService.addInfoSet("resource", new InfoSet()));
    try {
      sourceService.addInfoSet("unknown", new InfoSet());
      fail("Should have thrown the exception.");
    }
    catch (ServiceException e) {
      assertEquals("unknown source id", e.getMessage());
      assertEquals("anyhow", e.getAnotherMessage());
    }

    PersonService personService = new PersonServiceImpl("http://localhost:" + port + "/" + context + "/PersonServiceService", new MTOMFeature());
    ArrayList<String> ids = new ArrayList<String>(Arrays.asList("id1", "id2", "id3", "id4"));
    Collection persons = personService.readPersons(ids);
    for (Object o : persons) {
      Person person = (Person) o;
      assertTrue(ids.remove(person.getId()));
      assertEquals(new Date(1L), ((Event) person.getEvents().iterator().next()).getDate());
    }

    Collection<Person> empty = personService.readPersons(null);
    assertTrue(empty == null || empty.isEmpty());

    personService.deletePerson("somebody");
    try {
      personService.deletePerson(null);
      fail("Should have thrown the exception.");
    }
    catch (ServiceException e) {
      assertEquals("a person id must be supplied", e.getMessage());
      assertEquals("no person id.", e.getAnotherMessage());
    }

    Person person = new Person();
    person.setId("new-person");
    assertEquals("new-person", personService.storePerson(person).getId());

    byte[] pixBytes = "this is a bunch of bytes that I would like to make sure are serialized correctly so that I can prove out that attachments are working properly".getBytes();
    person.setPicture(new DataHandler(pixBytes, "image/jpg"));

    DataHandler returnedPix = personService.storePerson(person).getPicture();
    ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
    InputStream inputStream = returnedPix.getInputStream();
    int byteIn = inputStream.read();
    while (byteIn > -1) {
      bytesOut.write(byteIn);
      byteIn = inputStream.read();
    }

    RelationshipService relationshipService = new RelationshipServiceImpl("http://localhost:" + port + "/" + context + "/RelationshipServiceService");
    List list = relationshipService.getRelationships("someid");
    for (int i = 0; i < list.size(); i++) {
      Relationship relationship = (Relationship) list.get(i);
      assertEquals(String.valueOf(i), relationship.getId());
    }

    try {
      relationshipService.getRelationships("throw");
      fail("Should have thrown the relationship service exception, even though it wasn't annotated with @WebFault.");
    }
    catch (RelationshipException e) {
      assertEquals("hi", e.getMessage());
    }

    relationshipService.touch();

  }

}
