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
package com.webcohesion.enunciate.modules.spring_web.model;

import com.webcohesion.enunciate.facets.Facet;
import com.webcohesion.enunciate.facets.HasFacets;
import com.webcohesion.enunciate.javac.decorations.TypeMirrorDecorator;
import com.webcohesion.enunciate.javac.decorations.element.DecoratedExecutableElement;
import com.webcohesion.enunciate.javac.decorations.type.DecoratedDeclaredType;
import com.webcohesion.enunciate.javac.decorations.type.DecoratedTypeMirror;
import com.webcohesion.enunciate.javac.decorations.type.TypeMirrorUtils;
import com.webcohesion.enunciate.javac.decorations.type.TypeVariableContext;
import com.webcohesion.enunciate.javac.javadoc.JavaDoc;
import com.webcohesion.enunciate.metadata.rs.RequestHeader;
import com.webcohesion.enunciate.metadata.rs.*;
import com.webcohesion.enunciate.modules.spring_web.EnunciateSpringWebContext;
import com.webcohesion.enunciate.util.AnnotationUtils;
import com.webcohesion.enunciate.util.TypeHintUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;

import javax.annotation.security.RolesAllowed;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import java.lang.annotation.IncompleteAnnotationException;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A JAX-RS resource method.
 *
 * @author Ryan Heaton
 */
public class RequestMapping extends DecoratedExecutableElement implements HasFacets, PathContext {

  private static final Pattern CONTEXT_PARAM_PATTERN = Pattern.compile("\\{([^\\}]+)\\}");

  private final EnunciateSpringWebContext context;
  private final List<PathSegment> pathSegments;
  private final String label;
  private final Set<String> httpMethods;
  private final Set<String> consumesMediaTypes;
  private final Set<String> producesMediaTypes;
  private final SpringController parent;
  private final Set<RequestParameter> requestParameters;
  private final ResourceEntityParameter entityParameter;
  private final Map<String, Object> metaData = new HashMap<String, Object>();
  private final List<? extends ResponseCode> statusCodes;
  private final List<? extends ResponseCode> warnings;
  private final Map<String, String> responseHeaders = new HashMap<String, String>();
  private final ResourceRepresentationMetadata representationMetadata;
  private final Set<Facet> facets = new TreeSet<Facet>();

  public RequestMapping(List<PathSegment> pathSegments, org.springframework.web.bind.annotation.RequestMapping mappingInfo, ExecutableElement delegate, SpringController parent, TypeVariableContext variableContext, EnunciateSpringWebContext context) {
    super(delegate, context.getContext().getProcessingEnvironment());
    this.context = context;
    this.pathSegments = pathSegments;

    //initialize first with all methods.
    EnumSet<RequestMethod> httpMethods = EnumSet.allOf(RequestMethod.class);

    RequestMethod[] methods = mappingInfo.method();
    if (methods.length > 0) {
      httpMethods.retainAll(Arrays.asList(methods));
    }

    httpMethods.retainAll(parent.getApplicableMethods());

    if (httpMethods.isEmpty()) {
      throw new IllegalStateException(parent.getQualifiedName() + "." + getSimpleName() + ": no applicable request methods.");
    }

    this.httpMethods = new TreeSet<String>();
    for (RequestMethod httpMethod : httpMethods) {
      this.httpMethods.add(httpMethod.name());
    }

    Set<String> consumes = new TreeSet<String>();
    String[] consumesInfo = mappingInfo.consumes();
    if (consumesInfo.length > 0) {
      for (String mediaType : consumesInfo) {
        if (mediaType.startsWith("!")) {
          continue;
        }

        int colonIndex = mediaType.indexOf(';');
        if (colonIndex > 0) {
          mediaType = mediaType.substring(0, colonIndex);
        }

        consumes.add(mediaType);
      }

      if (consumes.isEmpty()) {
        consumes.add("*/*");
      }
    }
    else {
      consumes = parent.getConsumesMime();
    }
    this.consumesMediaTypes = consumes;

    Set<String> produces = new TreeSet<String>();
    String[] producesInfo = mappingInfo.produces();
    if (producesInfo.length > 0) {
      for (String mediaType : producesInfo) {
        if (mediaType.startsWith("!")) {
          continue;
        }

        int colonIndex = mediaType.indexOf(';');
        if (colonIndex > 0) {
          mediaType = mediaType.substring(0, colonIndex);
        }

        produces.add(mediaType);
      }

      if (produces.isEmpty()) {
        produces.add("*/*");
      }
    }
    else {
      produces = parent.getProducesMime();
    }
    this.producesMediaTypes = produces;

    String label = null;
    ResourceLabel resourceLabel = delegate.getAnnotation(ResourceLabel.class);
    if (resourceLabel != null) {
      label = resourceLabel.value();
      if ("##default".equals(label)) {
        label = null;
      }
    }


    ResourceEntityParameter entityParameter = null;
    ResourceRepresentationMetadata outputPayload = null;
    Set<RequestParameter> requestParameters = new TreeSet<RequestParameter>();
    ArrayList<ResponseCode> statusCodes = new ArrayList<ResponseCode>();
    ArrayList<ResponseCode> warnings = new ArrayList<ResponseCode>();

    Set<SpringControllerAdvice> advice = this.context.getAdvice();
    for (SpringControllerAdvice controllerAdvice : advice) {
      List<RequestMappingAdvice> requestAdvice = controllerAdvice.findRequestMappingAdvice(this);
      for (RequestMappingAdvice mappingAdvice : requestAdvice) {
        entityParameter = mappingAdvice.getEntityParameter();
        outputPayload = mappingAdvice.getRepresentationMetadata();
        requestParameters.addAll(mappingAdvice.getRequestParameters());
        statusCodes.addAll(mappingAdvice.getStatusCodes());
        warnings.addAll(mappingAdvice.getWarnings());
        this.responseHeaders.putAll(mappingAdvice.getResponseHeaders());
      }
    }

    for (VariableElement parameterDeclaration : getParameters()) {
      if (parameterDeclaration.getAnnotation(RequestBody.class) != null) {
        entityParameter = new ResourceEntityParameter(parameterDeclaration, variableContext, context);
      }
      else {
        requestParameters.addAll(RequestParameterFactory.getRequestParameters(this, parameterDeclaration, this));
      }
    }

    DecoratedTypeMirror<?> returnType;
    TypeHint hintInfo = getAnnotation(TypeHint.class);
    if (hintInfo != null) {
      returnType = (DecoratedTypeMirror) TypeHintUtils.getTypeHint(hintInfo, this.env, null);
      if (returnType != null) {
        returnType.setDocComment(((DecoratedTypeMirror) getReturnType()).getDocComment());
      }
    }
    else {
      returnType = (DecoratedTypeMirror) getReturnType();
      String docComment = returnType.getDocComment();

      if (returnType instanceof DecoratedDeclaredType && (returnType.isInstanceOf(Callable.class) || returnType.isInstanceOf(DeferredResult.class) || returnType.isInstanceOf("org.springframework.util.concurrent.ListenableFuture"))) {
        //attempt unwrap callable and deferred results.
        List<? extends TypeMirror> typeArgs = ((DecoratedDeclaredType) returnType).getTypeArguments();
        returnType = (typeArgs != null && typeArgs.size() == 1) ? (DecoratedTypeMirror<?>) TypeMirrorDecorator.decorate(typeArgs.get(0), this.env) : TypeMirrorUtils.objectType(this.env);
      }

      boolean returnsResponseBody = getAnnotation(ResponseBody.class) != null
        || parent.getAnnotation(ResponseBody.class) != null
        || parent.getAnnotation(RestController.class) != null;

      if (returnType instanceof DecoratedDeclaredType && returnType.isInstanceOf("org.springframework.http.HttpEntity")) {
        DecoratedDeclaredType entity = (DecoratedDeclaredType) returnType;
        List<? extends TypeMirror> typeArgs = ((DecoratedDeclaredType) entity).getTypeArguments();
        returnType = (typeArgs != null && typeArgs.size() == 1) ? (DecoratedTypeMirror<?>) TypeMirrorDecorator.decorate(typeArgs.get(0), this.env) : TypeMirrorUtils.objectType(this.env);
      }
      else if (!returnsResponseBody) {
        //doesn't return response body; no way to tell what's being returned.
        returnType = TypeMirrorUtils.objectType(this.env);
      }

      if (getJavaDoc().get("returnWrapped") != null) { //support jax-doclets. see http://jira.codehaus.org/browse/ENUNCIATE-690
        String fqn = getJavaDoc().get("returnWrapped").get(0);
        boolean array = false;
        if (fqn.endsWith("[]")) {
          array = true;
          fqn = fqn.substring(0, fqn.length() - 2);
        }

        TypeElement type = env.getElementUtils().getTypeElement(fqn);
        if (type != null) {
          returnType = (DecoratedTypeMirror) TypeMirrorDecorator.decorate(env.getTypeUtils().getDeclaredType(type), this.env);

          if (array) {
            returnType = (DecoratedTypeMirror) TypeMirrorDecorator.decorate(env.getTypeUtils().getArrayType(returnType), this.env);
          }
        }
      }

      //now resolve any type variables.
      returnType = (DecoratedTypeMirror) TypeMirrorDecorator.decorate(variableContext.resolveTypeVariables(returnType, this.env), this.env);
      returnType.setDocComment(docComment);
    }

    outputPayload = returnType == null || returnType.isVoid() ? outputPayload : new ResourceRepresentationMetadata(returnType);


    JavaDoc.JavaDocTagList doclets = getJavaDoc().get("RequestHeader"); //support jax-doclets. see http://jira.codehaus.org/browse/ENUNCIATE-690
    if (doclets != null) {
      for (String doclet : doclets) {
        int firstspace = doclet.indexOf(' ');
        String header = firstspace > 0 ? doclet.substring(0, firstspace) : doclet;
        String doc = ((firstspace > 0) && (firstspace + 1 < doclet.length())) ? doclet.substring(firstspace + 1) : "";
        requestParameters.add(new ExplicitRequestParameter(this, doc, header, ResourceParameterType.HEADER, context));
      }
    }

    List<JavaDoc.JavaDocTagList> inheritedDoclets = AnnotationUtils.getJavaDocTags("RequestHeader", parent);
    for (JavaDoc.JavaDocTagList inheritedDoclet : inheritedDoclets) {
      for (String doclet : inheritedDoclet) {
        int firstspace = doclet.indexOf(' ');
        String header = firstspace > 0 ? doclet.substring(0, firstspace) : doclet;
        String doc = ((firstspace > 0) && (firstspace + 1 < doclet.length())) ? doclet.substring(firstspace + 1) : "";
        requestParameters.add(new ExplicitRequestParameter(this, doc, header, ResourceParameterType.HEADER, context));
      }
    }

    RequestHeaders requestHeaders = getAnnotation(RequestHeaders.class);
    if (requestHeaders != null) {
      for (RequestHeader header : requestHeaders.value()) {
        requestParameters.add(new ExplicitRequestParameter(this, header.description(), header.name(), ResourceParameterType.HEADER, context));
      }
    }

    List<RequestHeaders> inheritedRequestHeaders = AnnotationUtils.getAnnotations(RequestHeaders.class, parent);
    for (RequestHeaders inheritedRequestHeader : inheritedRequestHeaders) {
      for (RequestHeader header : inheritedRequestHeader.value()) {
        requestParameters.add(new ExplicitRequestParameter(this, header.description(), header.name(), ResourceParameterType.HEADER, context));
      }
    }

    StatusCodes codes = getAnnotation(StatusCodes.class);
    if (codes != null) {
      for (com.webcohesion.enunciate.metadata.rs.ResponseCode code : codes.value()) {
        ResponseCode rc = new ResponseCode(this);
        rc.setCode(code.code());
        rc.setCondition(code.condition());
        for (ResponseHeader header : code.additionalHeaders()) {
          rc.setAdditionalHeader(header.name(), header.description());
        }
        rc.setType((DecoratedTypeMirror) TypeHintUtils.getTypeHint(code.type(), this.env, null));
        statusCodes.add(rc);
      }
    }

    ResponseStatus responseStatus = getAnnotation(ResponseStatus.class);
    if (responseStatus != null) {
      HttpStatus code = responseStatus.value();
      if (code == HttpStatus.INTERNAL_SERVER_ERROR) {
        try {
          code = responseStatus.code();
        }
        catch (IncompleteAnnotationException e) {
          //fall through; 'responseStatus.code' was added in 4.2.
        }
      }
      ResponseCode rc = new ResponseCode(this);
      rc.setCode(code.value());
      String reason = responseStatus.reason();
      if (!reason.isEmpty()) {
        rc.setCondition(reason);
      }
      statusCodes.add(rc);
    }

    List<StatusCodes> inheritedStatusCodes = AnnotationUtils.getAnnotations(StatusCodes.class, parent);
    for (StatusCodes inheritedStatusCode : inheritedStatusCodes) {
      for (com.webcohesion.enunciate.metadata.rs.ResponseCode code : inheritedStatusCode.value()) {
        ResponseCode rc = new ResponseCode(this);
        rc.setCode(code.code());
        rc.setCondition(code.condition());
        for (ResponseHeader header : code.additionalHeaders()) {
          rc.setAdditionalHeader(header.name(), header.description());
        }
        rc.setType((DecoratedTypeMirror) TypeHintUtils.getTypeHint(code.type(), this.env, null));
        statusCodes.add(rc);
      }
    }

    doclets = getJavaDoc().get("HTTP");
    if (doclets != null) {
      for (String doclet : doclets) {
        int firstspace = doclet.indexOf(' ');
        String code = firstspace > 0 ? doclet.substring(0, firstspace) : doclet;
        String doc = ((firstspace > 0) && (firstspace + 1 < doclet.length())) ? doclet.substring(firstspace + 1) : "";
        try {
          ResponseCode rc = new ResponseCode(this);
          rc.setCode(Integer.parseInt(code));
          rc.setCondition(doc);
          statusCodes.add(rc);
        }
        catch (NumberFormatException e) {
          //fall through...
        }
      }
    }

    inheritedDoclets = AnnotationUtils.getJavaDocTags("HTTP", parent);
    for (JavaDoc.JavaDocTagList inheritedDoclet : inheritedDoclets) {
      for (String doclet : inheritedDoclet) {
        int firstspace = doclet.indexOf(' ');
        String code = firstspace > 0 ? doclet.substring(0, firstspace) : doclet;
        String doc = ((firstspace > 0) && (firstspace + 1 < doclet.length())) ? doclet.substring(firstspace + 1) : "";
        try {
          ResponseCode rc = new ResponseCode(this);
          rc.setCode(Integer.parseInt(code));
          rc.setCondition(doc);
          statusCodes.add(rc);
        }
        catch (NumberFormatException e) {
          //fall through...
        }
      }
    }

    Warnings warningInfo = getAnnotation(Warnings.class);
    if (warningInfo != null) {
      for (com.webcohesion.enunciate.metadata.rs.ResponseCode code : warningInfo.value()) {
        ResponseCode rc = new ResponseCode(this);
        rc.setCode(code.code());
        rc.setCondition(code.condition());
        warnings.add(rc);
      }
    }

    List<Warnings> inheritedWarnings = AnnotationUtils.getAnnotations(Warnings.class, parent);
    for (Warnings inheritedWarning : inheritedWarnings) {
      for (com.webcohesion.enunciate.metadata.rs.ResponseCode code : inheritedWarning.value()) {
        ResponseCode rc = new ResponseCode(this);
        rc.setCode(code.code());
        rc.setCondition(code.condition());
        warnings.add(rc);
      }
    }

    doclets = getJavaDoc().get("HTTPWarning");
    if (doclets != null) {
      for (String doclet : doclets) {
        int firstspace = doclet.indexOf(' ');
        String code = firstspace > 0 ? doclet.substring(0, firstspace) : doclet;
        String doc = ((firstspace > 0) && (firstspace + 1 < doclet.length())) ? doclet.substring(firstspace + 1) : "";
        try {
          ResponseCode rc = new ResponseCode(this);
          rc.setCode(Integer.parseInt(code));
          rc.setCondition(doc);
          warnings.add(rc);
        }
        catch (NumberFormatException e) {
          //fall through...
        }
      }
    }

    inheritedDoclets = AnnotationUtils.getJavaDocTags("HTTPWarning", parent);
    for (JavaDoc.JavaDocTagList inheritedDoclet : inheritedDoclets) {
      for (String doclet : inheritedDoclet) {
        int firstspace = doclet.indexOf(' ');
        String code = firstspace > 0 ? doclet.substring(0, firstspace) : doclet;
        String doc = ((firstspace > 0) && (firstspace + 1 < doclet.length())) ? doclet.substring(firstspace + 1) : "";
        try {
          ResponseCode rc = new ResponseCode(this);
          rc.setCode(Integer.parseInt(code));
          rc.setCondition(doc);
          warnings.add(rc);
        }
        catch (NumberFormatException e) {
          //fall through...
        }
      }
    }

    ResponseHeaders responseHeaders = getAnnotation(ResponseHeaders.class);
    if (responseHeaders != null) {
      for (ResponseHeader header : responseHeaders.value()) {
        this.responseHeaders.put(header.name(), header.description());
      }
    }

    List<ResponseHeaders> inheritedResponseHeaders = AnnotationUtils.getAnnotations(ResponseHeaders.class, parent);
    for (ResponseHeaders inheritedResponseHeader : inheritedResponseHeaders) {
      for (ResponseHeader header : inheritedResponseHeader.value()) {
        this.responseHeaders.put(header.name(), header.description());
      }
    }

    doclets = getJavaDoc().get("ResponseHeader");
    if (doclets != null) {
      for (String doclet : doclets) {
        int firstspace = doclet.indexOf(' ');
        String header = firstspace > 0 ? doclet.substring(0, firstspace) : doclet;
        String doc = ((firstspace > 0) && (firstspace + 1 < doclet.length())) ? doclet.substring(firstspace + 1) : "";
        this.responseHeaders.put(header, doc);
      }
    }

    inheritedDoclets = AnnotationUtils.getJavaDocTags("ResponseHeader", parent);
    for (JavaDoc.JavaDocTagList inheritedDoclet : inheritedDoclets) {
      for (String doclet : inheritedDoclet) {
        int firstspace = doclet.indexOf(' ');
        String header = firstspace > 0 ? doclet.substring(0, firstspace) : doclet;
        String doc = ((firstspace > 0) && (firstspace + 1 < doclet.length())) ? doclet.substring(firstspace + 1) : "";
        this.responseHeaders.put(header, doc);
      }
    }

    this.entityParameter = entityParameter;
    this.requestParameters = requestParameters;
    this.label = label;
    this.parent = parent;
    this.statusCodes = statusCodes;
    this.warnings = warnings;
    this.representationMetadata = outputPayload;
    this.facets.addAll(Facet.gatherFacets(delegate));
    this.facets.addAll(parent.getFacets());
  }

  protected static HashMap<String, String> parseParamComments(String tagName, JavaDoc jd) {
    HashMap<String, String> paramComments = new HashMap<String, String>();
    if (jd.get(tagName) != null) {
      for (String paramDoc : jd.get(tagName)) {
        paramDoc = paramDoc.trim().replaceFirst("\\s+", " ");
        int spaceIndex = paramDoc.indexOf(' ');
        if (spaceIndex == -1) {
          spaceIndex = paramDoc.length();
        }

        String param = paramDoc.substring(0, spaceIndex);
        String paramComment = "";
        if ((spaceIndex + 1) < paramDoc.length()) {
          paramComment = paramDoc.substring(spaceIndex + 1);
        }

        paramComments.put(param, paramComment);
      }
    }
    return paramComments;
  }

  @Override
  protected HashMap<String, String> loadParamsComments(JavaDoc jd) {
    HashMap<String, String> paramRESTComments = parseParamComments("RSParam", jd);
    HashMap<String, String> paramComments = parseParamComments("param", jd);
    paramComments.putAll(paramRESTComments);
    return paramComments;
  }

  public EnunciateSpringWebContext getContext() {
    return context;
  }

  /**
   * The HTTP methods for invoking the method.
   *
   * @return The HTTP methods for invoking the method.
   */
  public Set<String> getHttpMethods() {
    return httpMethods;
  }

  /**
   * Get the path components for this resource method.
   *
   * @return The path components.
   */
  public List<PathSegment> getPathSegments() {
    return this.pathSegments;
  }

  /**
   * Builds the full URI path to this resource method.
   *
   * @return the full URI path to this resource method.
   */
  public String getFullpath() {
    StringBuilder builder = new StringBuilder();
    for (PathSegment pathSegment : getPathSegments()) {
      builder.append(pathSegment.getValue());
    }
    return builder.toString();
  }

  /**
   * The servlet pattern that can be applied to access this resource method.
   *
   * @return The servlet pattern that can be applied to access this resource method.
   */
  public String getServletPattern() {
    StringBuilder builder = new StringBuilder();
    String fullPath = getFullpath();
    Matcher pathParamMatcher = CONTEXT_PARAM_PATTERN.matcher(fullPath);
    if (pathParamMatcher.find()) {
      builder.append(fullPath, 0, pathParamMatcher.start()).append("*");
    }
    else {
      builder.append(fullPath);
    }
    return builder.toString();
  }

  /**
   * The label for this resource method, if it exists.
   *
   * @return The subpath for this resource method, if it exists.
   */
  public String getLabel() {
    return label;
  }

  /**
   * The resource that holds this resource method.
   *
   * @return The resource that holds this resource method.
   */
  public SpringController getParent() {
    return parent;
  }

  /**
   * The MIME types that are consumed by this method.
   *
   * @return The MIME types that are consumed by this method.
   */
  public Set<String> getConsumesMediaTypes() {
    return consumesMediaTypes;
  }

  /**
   * The MIME types that are produced by this method.
   *
   * @return The MIME types that are produced by this method.
   */
  public Set<String> getProducesMediaTypes() {
    return producesMediaTypes;
  }

  /**
   * The list of resource parameters that this method requires to be invoked.
   *
   * @return The list of resource parameters that this method requires to be invoked.
   */
  public Set<RequestParameter> getRequestParameters() {
    return this.requestParameters;
  }

  /**
   * The entity parameter.
   *
   * @return The entity parameter, or null if none.
   */
  public ResourceEntityParameter getEntityParameter() {
    return entityParameter;
  }

  /**
   * The output payload for this resource.
   *
   * @return The output payload for this resource.
   */
  public ResourceRepresentationMetadata getRepresentationMetadata() {
    return this.representationMetadata;
  }

  /**
   * The potential status codes.
   *
   * @return The potential status codes.
   */
  public List<? extends ResponseCode> getStatusCodes() {
    return this.statusCodes;
  }

  /**
   * The potential warnings.
   *
   * @return The potential warnings.
   */
  public List<? extends ResponseCode> getWarnings() {
    return this.warnings;
  }

  /**
   * The metadata associated with this resource.
   *
   * @return The metadata associated with this resource.
   */
  public Map<String, Object> getMetaData() {
    return Collections.unmodifiableMap(this.metaData);
  }

  /**
   * Put metadata associated with this resource.
   *
   * @param name The name of the metadata.
   * @param data The data.
   */
  public void putMetaData(String name, Object data) {
    this.metaData.put(name, data);
  }

  /**
   * The response headers that are expected on this resource method.
   *
   * @return The response headers that are expected on this resource method.
   */
  public Map<String, String> getResponseHeaders() {
    return responseHeaders;
  }

  /**
   * The facets here applicable.
   *
   * @return The facets here applicable.
   */
  public Set<Facet> getFacets() {
    return facets;
  }

  /**
   * The security roles for this method.
   *
   * @return The security roles for this method.
   */
  public Set<String> getSecurityRoles() {
    TreeSet<String> roles = new TreeSet<String>();
    RolesAllowed rolesAllowed = getAnnotation(RolesAllowed.class);
    if (rolesAllowed != null) {
      Collections.addAll(roles, rolesAllowed.value());
    }

    SpringController parent = getParent();
    if (parent != null) {
      roles.addAll(parent.getSecurityRoles());
    }
    return roles;
  }

}
