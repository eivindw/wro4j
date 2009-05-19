/*
 * Copyright (c) 2008 ISDC! Romania. All rights reserved.
 */
package ro.isdc.wro.model.impl;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Source;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;

import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import ro.isdc.wro.exception.RecursiveGroupDefinitionException;
import ro.isdc.wro.exception.WroRuntimeException;
import ro.isdc.wro.http.ContextHolder;
import ro.isdc.wro.manager.WroSettings;
import ro.isdc.wro.model.Group;
import ro.isdc.wro.model.WroModel;
import ro.isdc.wro.model.WroModelFactory;
import ro.isdc.wro.resource.Resource;
import ro.isdc.wro.resource.ResourceType;
import ro.isdc.wro.resource.UriLocator;
import ro.isdc.wro.resource.UriLocatorFactory;

/**
 * Model factory implementation. Creates a WroModel object, based on an xml. This xml contains the
 * description of all groups.
 *
 * @author alexandru.objelean / ISDC! Romania
 * @version $Revision: $
 * @date $Date: $
 * @created Created on Nov 3, 2008
 */
public class XmlModelFactory implements WroModelFactory {
  /**
   * Logger for this class.
   */
  private static final Log log = LogFactory.getLog(XmlModelFactory.class);

  /**
   * Default xml to parse.
   */
  protected static final String XML_CONFIG_FILE = "wro.xml";

  /**
   * Default xml to parse.
   */
  private static final String XML_SCHEMA_FILE = "ro/isdc/wro/wro.xsd";

  /**
   * Group tag used in xml.
   */
  private static final String TAG_GROUP = "group";

  /**
   * CSS tag used in xml.
   */
  private static final String TAG_CSS = "css";

  /**
   * JS tag used in xml.
   */
  private static final String TAG_JS = "js";

  /**
   * GroupRef tag used in xml.
   */
  private static final String TAG_GROUP_REF = "group-ref";

  /**
   * Group name attribute used in xml.
   */
  private static final String ATTR_GROUP_NAME = "name";

  /**
   * Browsers attribute used in xml.
   */
  private static final String ATTR_BROWSERS = "browsers";

  /**
   * Browsers attribute used in xml.
   */
  private static final String ATTR_NEGATED = "negated";

  /**
   * UriLocatorFactory. Used to create a resource based on its type.
   */
  private UriLocatorFactory uriLocatorFactory;

  /**
   * Map between the group name and corresponding element. Hold the map<GroupName, Element> of all group
   * nodes to access any element.
   */
  final Map<String, Element> allGroupElements = new HashMap<String, Element>();

  /**
   * List of groups which are currently processing and are partially parsed. This list is useful in order to
   * catch infinite recurse group reference.
   */
  final List<String> processingGroups = new ArrayList<String>();

  /**
   * Reference to cached model configuration. Using volatile keyword fix the problem with double-checked
   * locking in JDK 1.5.
   */
  private volatile String configString;

  /**
   * Dynamic parameters regexp pattern
   */
  private static Pattern paramPattern = Pattern.compile("\\$\\{(.+?)\\}");

  /**
   * {@inheritDoc}
   */
  public synchronized WroModel getInstance(final UriLocatorFactory uriLocatorFactory) {
    log.debug("<getInstance>");
    try {
      if (uriLocatorFactory == null) {
        throw new IllegalArgumentException("uriLocatorFactory cannot be NULL!");
      }
      this.uriLocatorFactory = uriLocatorFactory;

      return constructModel();
    } finally {
      log.debug("</getInstance>");
    }
  }

  /**
   * Build model from scratch after xml is parsed.
   *
   * @return new instance of model.
   */
  private WroModel constructModel() {
    // TODO return a single instance based on some configuration?
    Document document = null;
    try {
      final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      // factory.setSchema(getSchema());
      // factory.setValidating(true);

      // cache the config content for DEPLOYMENT mode
      InputStream configStream = getCachedConfigStream();

      // replace the expressions with the values
      document = factory.newDocumentBuilder().parse(replaceExpressions(configStream));

      // IOUtils.copy(configStream, System.out);
      validate(document);
      document.getDocumentElement().normalize();
    } catch (final IOException e) {
      throw new WroRuntimeException("Cannot find XML to parse", e);
    } catch (final SAXException e) {
      throw new WroRuntimeException("Parsing error", e);
    } catch (final ParserConfigurationException e) {
      throw new WroRuntimeException("Parsing error", e);
    }
    initGroupMap(document);
    // TODO cache model based on application Mode (DEPLOYMENT, DEVELOPMENT)
    final WroModel model = createModel();
    log.debug("</getInstance>");
    return model;
  }

  /**
   * Get the configuration content as a stream based on a input stream from a configuration file or a cached
   * version string.
   *
   * @return the cached version InputStrream for the configuration.
   */
  private InputStream getCachedConfigStream() {
    if (WroSettings.getConfiguration().isDeployment()) {
      if (this.configString == null) {
        synchronized (this) {
          if (this.configString == null) {
            InputStream configStream = getConfigResourceAsStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(configStream));
            StringBuilder sb = new StringBuilder();

            String line = null;
            try {
              while ((line = reader.readLine()) != null) {
                sb.append(line + "\n");
              }
            } catch (IOException e) {
              e.printStackTrace();
            } finally {
              try {
                configStream.close();
              } catch (IOException e) {
                e.printStackTrace();
              }
            }
            this.configString = sb.toString();
          }
        }
      }
    } else {
      return getConfigResourceAsStream();
    }

    return new ByteArrayInputStream(this.configString.getBytes());
  }

  /**
   * Try to replace the config expressions with suitable values.
   *
   * @param configStream
   * @return
   * @throws IOException
   */
  private InputStream replaceExpressions(InputStream configStream) throws IOException {
    BufferedReader in = new BufferedReader(new InputStreamReader(configStream));

    StringBuilder builder = new StringBuilder();
    String line = null;
    Matcher matcher = null;

    // process the config line by line
    while ((line = in.readLine()) != null) {
      // process line
      matcher = paramPattern.matcher(line);
      // look up all the parameters in the line
      while (matcher.find()) {
        String parameter = matcher.group(1);
        // replace the param place holder with the value
        line = matcher.replaceFirst(acquireParamValue(parameter));
        // check for next parameter
        matcher = paramPattern.matcher(line);
      }

      builder.append(line);
      builder.append("\n");
    }
    ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(builder.toString().getBytes());
    return byteArrayInputStream;
  }

  /**
   * Get the config parameters from request or session.
   *
   * @param parameter
   * @return
   */
  private String acquireParamValue(String parameter) {
    HttpServletRequest request = ContextHolder.REQUEST_HOLDER.get();

    // attempt to get the parameter from the request first
    String paramValue = request.getParameter(parameter);

    if (paramValue == null) {
      // not a request param, now attempt to get it from session
      paramValue = (String) request.getSession().getAttribute(parameter);
    }

    // as last resort will set the value as the parameter name
    if (paramValue == null) {
      paramValue = parameter;
    }

    return paramValue;
  }

  /**
   * @return Schema
   */
  private Schema getSchema() throws IOException, SAXException {
    // create a SchemaFactory capable of understanding WXS schemas
    final SchemaFactory factory = SchemaFactory
    // .newInstance(schemaLanguage)
        .newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);

    // load a WXS schema, represented by a Schema instance
    final Source schemaFile = new StreamSource(getResourceAsStream(XML_SCHEMA_FILE));
    IOUtils.copy(getResourceAsStream(XML_SCHEMA_FILE), System.out);
    final Schema schema = factory.newSchema(schemaFile);
    return schema;
  }

  /**
   * Override this method, in order to provide different xml definition file name.
   *
   * @return name of the xml file containing group & resource definition.
   */
  protected InputStream getConfigResourceAsStream() {
    return getResourceAsStream(XML_CONFIG_FILE);
  }

  /**
   * Initialize the map
   */
  private void initGroupMap(final Document document) {
    final NodeList groupNodeList = document.getElementsByTagName(TAG_GROUP);
    for (int i = 0; i < groupNodeList.getLength(); i++) {
      final Element groupElement = (Element) groupNodeList.item(i);
      final String name = groupElement.getAttribute(ATTR_GROUP_NAME);
      allGroupElements.put(name, groupElement);
    }
  }

  /**
   * Parse the document and creates the model.
   *
   * @param document to parse.
   * @return {@link WroModel} object.
   */
  private WroModel createModel() {
    final WroModel model = new WroModel();
    final List<Group> groups = new ArrayList<Group>();
    for (final Element element : allGroupElements.values()) {
      parseGroup(element, groups);
    }
    model.setGroups(groups);
    return model;
  }

  /**
   * Recursive method. Add the parsed element group to the group collection. If the group contains group-ref
   * element, parse recursively this group.
   *
   * @param element Group Element to parse.
   * @param groups list of parsed groups where the parsed group is added..
   * @return list of resources associated with this resource
   */
  private List<Resource> parseGroup(final Element element, final List<Group> groups) {
    log.debug("<parseGroup>");
    final String name = element.getAttribute(ATTR_GROUP_NAME);
    if (processingGroups.contains(name)) {
      throw new RecursiveGroupDefinitionException("Infinite Recursion detected for the group: " + name
          + ". Recursion path: " + processingGroups);
    }
    processingGroups.add(name);
    log.debug("\tgroupName=" + name);
    // skip if this group is already parsed
    final Group parsedGroup = getGroupByName(name, groups);
    if (parsedGroup != null) {
      // remove before returning
      // this group is parsed, remove from unparsed groups collection
      processingGroups.remove(name);
      return parsedGroup.getResources();
    }
    final Group group = new Group();
    group.setName(name);
    final List<Resource> resources = new ArrayList<Resource>();
    final NodeList resourceNodeList = element.getChildNodes();
    for (int i = 0; i < resourceNodeList.getLength(); i++) {
      final Node node = resourceNodeList.item(i);
      if (node instanceof Element) {
        final Element resourceElement = (Element) node;
        parseResource(resourceElement, resources, groups);
      }
    }
    group.setResources(resources);
    // this group is parsed, remove from unparsed collection
    processingGroups.remove(name);
    groups.add(group);
    log.debug("</parseGroup>");
    return resources;
  }

  /**
   * Check if the group with name <code>name</code> was already parsed and returns Group object with it's
   * resources initialized.
   *
   * @param name the group to check.
   * @param groups list of parsed groups.
   * @return parsed Group by it's name.
   */
  private static Group getGroupByName(final String name, final List<Group> groups) {
    for (final Group group : groups) {
      if (name.equals(group.getName())) {
        return group;
      }
    }
    return null;
  }

  public static void main(final String[] args) throws IOException {
    IOUtils.copy(getResourceAsStream(XML_SCHEMA_FILE), System.out);
  }

  /**
   * Creates a resource from a given resourceElement. It can be css, js. If resource tag name is group-ref,
   * the method will start a recursive computation.
   *
   * @param resourceElement
   * @param resources list of parsed resources where the parsed resource is added.
   */
  private void parseResource(final Element resourceElement, final List<Resource> resources,
      final List<Group> groups) {
    log.debug("<parseResource>");
    ResourceType type = null;
    final String tagName = resourceElement.getTagName();
    final String uri = resourceElement.getTextContent();
    log.debug("\ttagName=" + tagName);
    log.debug("\turi=" + uri);
    if (TAG_JS.equals(tagName)) {
      type = ResourceType.JS;
    } else if (TAG_CSS.equals(tagName)) {
      type = ResourceType.CSS;
    } else if (TAG_GROUP_REF.equals(tagName)) {
      // uri in this case is the group name
      final Element groupElement = allGroupElements.get(uri);
      log.debug("\tparse groupRef: " + uri + ": " + groupElement);
      resources.addAll(parseGroup(groupElement, groups));
    } else {
      // should not ever happen due to validation of xml.
      throw new WroRuntimeException("Usupported resource type: " + tagName);
    }
    log.debug("\ttype=" + type);
    if (type != null) {
      final UriLocator uriLocator = this.uriLocatorFactory.getInstance(uri);
      final Resource resource = new Resource(uri, type, uriLocator);

      if (checkResourceFilter(resourceElement)) {
        resources.add(resource);
      }
    }
    log.debug("</parseResource>");
  }

  /**
   * Check the attributes of the current resource element against the request properties.
   *
   * @param resourceElement current resource.
   * @return true is this resource is valid.
   */
  private boolean checkResourceFilter(final Element resourceElement) {
    String browsersAttr = resourceElement.getAttribute(ATTR_BROWSERS);
    String browserProp = getBrowser(ContextHolder.REQUEST_HOLDER.get().getHeader("User-Agent"));
    if (browsersAttr != null && !browsersAttr.equals("") && browserProp != null) {
      boolean negated = false;

      String negatedAttr = resourceElement.getAttribute(ATTR_NEGATED);
      if (negatedAttr != null && negatedAttr.equals("true")) {
        negated = true;
      }

      String[] browsers = browsersAttr.split(",");
      String[] uaTokens = browserProp.split("\\s");
      boolean found = false;
      for (String t : uaTokens) {
        for (String b : browsers) {
          if (t.startsWith(b)) {
            return !negated; // found ^ negated
          }
        }
      }
      return negated; // found ^ negated
    }

    return true;
  }

  /**
   * Retrieve the brwser name, user agent, etc as string.
   *
   * @param userAgentheader
   * @return
   */
  private String getBrowser(String userAgentheader) {
    String ua = userAgentheader.toLowerCase();
    Pattern iePattern = Pattern.compile("msie\\s(\\d\\.\\d)");
    Pattern opExPattern = Pattern.compile("opera|webtv");
    Matcher opExMatcher = opExPattern.matcher(ua);
    Matcher ieMatcher = iePattern.matcher(ua);

    if (!opExMatcher.find() && ieMatcher.find()) {
      return "ie" + ieMatcher.group(1);
    }

    StringBuffer buffer = new StringBuffer();
    Pattern ffPattern = Pattern.compile("firefox/(\\d+)");
    Matcher ffMatcher = ffPattern.matcher(ua);
    if (ffMatcher.find()) {
      buffer.append("ff" + ffMatcher.group(1)).append(" ");
    }
    Pattern opPattern = Pattern.compile("opera/(\\d+)");
    Matcher opMatcher = opPattern.matcher(ua);
    if (opMatcher.find()) {
      buffer.append("opera" + opMatcher.group(1)).append(" ");
    }
    Pattern kqPattern = Pattern.compile("konqueror");
    Matcher kqMatcher = kqPattern.matcher(ua);
    if (kqMatcher.find()) {
      buffer.append("konqueror").append(" ");
    }
    Pattern chPattern = Pattern.compile("chrome");
    Matcher chMatcher = chPattern.matcher(ua);
    if (chMatcher.find()) {
      buffer.append("chrome").append(" ").append(" ");
    }
    Pattern wkPattern = Pattern.compile("applewebkit/");
    Matcher wkMatcher = wkPattern.matcher(ua);
    if (wkMatcher.find()) {
      buffer.append("webkit").append(" ");
    }
    Pattern mozPattern = Pattern.compile("mozilla/");
    Matcher mozMatcher = wkPattern.matcher(ua);
    if (mozMatcher.find()) {
      buffer.append("mozilla").append(" ");
    }
    Pattern geckoPattern = Pattern.compile("gecko/");
    Matcher geckoMatcher = geckoPattern.matcher(ua);
    if (geckoMatcher.find()) {
      buffer.append("gecko").append(" ");
    }

    return buffer.toString();
  }

  /**
   * @return InputStream of the local resource from classpath.
   */
  private static InputStream getResourceAsStream(final String fileName) {
    return Thread.currentThread().getContextClassLoader().getResourceAsStream(fileName);
  }

  /**
   * Checks if xml structure is valid.
   *
   * @param document xml document to validate.
   */
  private void validate(final Document document) throws IOException, SAXException {
    IOUtils.copy(getResourceAsStream(XML_SCHEMA_FILE), System.out);
    final Schema schema = getSchema();
    // create a Validator instance, which can be used to validate an instance
    // document
    final Validator validator = schema.newValidator();
    // validate the DOM tree
    validator.validate(new DOMSource(document));
  }
}