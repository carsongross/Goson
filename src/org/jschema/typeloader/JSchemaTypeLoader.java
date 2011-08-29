package org.jschema.typeloader;

import gw.fs.IFile;
import gw.lang.reflect.IType;
import gw.lang.reflect.TypeLoaderBase;
import gw.lang.reflect.TypeSystem;
import gw.lang.reflect.module.IModule;
import gw.util.GosuExceptionUtil;
import gw.util.Pair;
import gw.util.concurrent.LazyVar;
import org.jschema.parser.JSONParser;
import org.jschema.typeloader.rpc.JSchemaCustomizedRPCType;
import org.jschema.typeloader.rpc.JSchemaRPCType;
import org.jschema.util.JSchemaUtils;

import java.io.*;
import java.util.*;

public class JSchemaTypeLoader extends TypeLoaderBase {

  private Map<String, IType> types = new HashMap<String, IType>();

  private static final String JSC_RPC_EXT = "jsc-rpc";
  private static final String JSC_EXT = "jsc";
  private static final String JSON_EXT = "json-type";

  public JSchemaTypeLoader(IModule env) {
    super(env);
  }

  @Override
  public IType getType(String fullyQualifiedName) {
    maybeInitTypes();
    if (fullyQualifiedName == null || types.get(fullyQualifiedName) == null) {
      return null;
    }
    IType iType = types.get( fullyQualifiedName );
    return TypeSystem.getOrCreateTypeReference( iType );
  }

  private void maybeInitTypes() {
    if (types.isEmpty()) {
      for (JsonFile jshFile : jscFiles.get()) {
        try {
          addTypes(types, new Stack<Map<String, String>>(), jshFile.rootTypeName, jshFile.content);
        } catch (Exception e) {
          throw GosuExceptionUtil.forceThrow(e);
        }
      }
      for (JsonFile jshRpcFile : jscRpcFiles.get()) {
        try {
          addRpcTypes(types, jshRpcFile.rootTypeName, jshRpcFile.content);
        } catch (Exception e) {
          throw GosuExceptionUtil.forceThrow(e);
        }
      }
      for (JsonFile jsonFile : jsonFiles.get()) {
        try {
          jsonFile.content = JSchemaUtils.convertJsonToJSchema(jsonFile.content);
          addTypes(types, new Stack<Map<String, String>>(), jsonFile.rootTypeName, jsonFile.content);
        } catch (Exception e) {
          throw GosuExceptionUtil.forceThrow(e);
        }
      }
    }
  }

  private void addTypes(Map<String, IType> types, Stack<Map<String, String>> typeDefs, String name, Object o) {
    if (o instanceof List && !((List)o).isEmpty()) {
      o = ((List)o).get(0);
    }
    if (o instanceof Map) {
      Map<Object, Object> jsonMap = (Map<Object, Object>)o;
      if (jsonMap.get("enum") != null) {
        types.put(name, new JSchemaEnumType(name, this, o));
      } else if (jsonMap.get("map_of") != null) {
        addTypes(types, typeDefs, name, jsonMap.get("map_of"));
      } else {
        try {
          typeDefs.push(new HashMap<String, String>());
          processTypeDefs(types, typeDefs, name, jsonMap);
          for (Object key : jsonMap.keySet()) {
            addTypes(types, typeDefs, name + "." + JSchemaUtils.convertJSONStringToGosuIdentifier(key.toString()),
              jsonMap.get(key));
          }
          types.put(name, new JSchemaType(name, this, o, copyTypeDefs(typeDefs)));
        } finally {
          typeDefs.pop();
        }
      }
    }
  }

  private Map<String, String> copyTypeDefs(Stack<Map<String, String>> typeDefs) {
    HashMap<String, String> allTypeDefs = new HashMap<String, String>();
    for (Map<String, String> typeDef : typeDefs) {
      allTypeDefs.putAll(typeDef);
    }
    return allTypeDefs;
  }

  private void processTypeDefs(Map<String, IType> types, Stack<Map<String, String>> typeDefs, String name, Map o) {
    Object currentTypeDefs = o.get(JSchemaUtils.JSCHEMA_TYPEDEFS_KEY);
    if (currentTypeDefs instanceof Map) {
      Set set = ((Map) currentTypeDefs).keySet();
      List<JSchemaType> previousTypeDefs = new ArrayList<JSchemaType>();
      for (Object typeDefTypeName : set) {
        String rawName = typeDefTypeName.toString();
        String relativeName = JSchemaUtils.convertJSONStringToGosuIdentifier(rawName);
        String fullyQualifiedName = name + "." + relativeName;
        typeDefs.peek().put(rawName, fullyQualifiedName);
        addTypes(types, typeDefs, fullyQualifiedName, ((Map) currentTypeDefs).get(typeDefTypeName));
        for (JSchemaType previousTypeDef : previousTypeDefs) {
          previousTypeDef.getTypeDefs().put(rawName, fullyQualifiedName);
        }
        previousTypeDefs.add((JSchemaType) types.get(fullyQualifiedName));
      }
    }
  }

  private void addRpcTypes(Map<String, IType> types, String name, Object o) {
    Stack<Map<String, String>> typeDefs = new Stack<Map<String, String>>();
    typeDefs.push(new HashMap<String, String>());
    if (o instanceof Map) {
      processTypeDefs(types, typeDefs, name, (Map) o);
      Object functions = ((Map) o).get("functions");
      if (functions instanceof List) {
        for (Object function : (List) functions) {
          if (function instanceof Map) {
            Map functionMap = (Map) function;
            Object str = functionMap.get("name");
            String functionTypeName =  name + "." + JSchemaUtils.convertJSONStringToGosuIdentifier(str.toString());

            // add parameter names
            Object args = functionMap.get("args");
            if (args instanceof List) {
              for (Object arg : (List) args) {
                if (arg instanceof Map) {
                  Set argSpecKeys = ((Map) arg).keySet();
                  for (Object key : argSpecKeys) {
                    if (key.equals("default") || key.equals("description")) {
                      continue;
                    } else {
                      addTypes(types,
                        typeDefs, functionTypeName + "." + JSchemaUtils.convertJSONStringToGosuIdentifier(key.toString()),
                        ((Map) arg).get(key));
                    }
                  }
                }

              }
            }
            // add the return type
            addTypes(types, typeDefs, functionTypeName, ((Map) function).get("returns"));
          }
        }
      }
      types.put(name, new JSchemaRPCType(name, this, o, typeDefs.peek()));
      types.put(name + JSchemaCustomizedRPCType.TYPE_SUFFIX, new JSchemaCustomizedRPCType(name + JSchemaCustomizedRPCType.TYPE_SUFFIX, this, o, typeDefs.peek()));
    }
  }

  @Override
  public Set<String> getAllTypeNames() {
    maybeInitTypes();
    return new HashSet<String>( types.keySet() );
  }

  @Override
  public List<String> getHandledPrefixes() {
    return Collections.emptyList();
  }

  /*
  * Default implementation to handle Gosu 0.9 reqs
  */
//  @Override TODO restore later
  public boolean handlesNonPrefixLoads() {
    return true;
  }

  private LazyVar<List<JsonFile>> jscFiles = new LazyVar<List<JsonFile>>() {
    @Override
    protected List<JsonFile> init() {
      return findFilesOfType(JSC_EXT);
    }
  };

  private LazyVar<List<JsonFile>> jscRpcFiles = new LazyVar<List<JsonFile>>() {
    @Override
    protected List<JsonFile> init() {
      return findFilesOfType(JSC_RPC_EXT);
    }
  };

  private LazyVar<List<JsonFile>> jsonFiles = new LazyVar<List<JsonFile>>() {
    @Override
    protected List<JsonFile> init() {
      return findFilesOfType(JSON_EXT);
    }
  };

  private List<JsonFile> findFilesOfType(String extension) {
    List<JsonFile> init = new java.util.ArrayList<JsonFile>();

    List<Pair<String, IFile>> files = getModule().getResourceAccess().findAllFilesByExtension(extension);
    for (Pair<String, IFile> pair : files) {
      JsonFile current = new JsonFile();

      String relativeNameAsFile = pair.getFirst();
      int trimmedLength = relativeNameAsFile.length() - extension.length() - 1;
      String typeName = relativeNameAsFile.replace('/', '.').replace('\\', '.').substring(0, trimmedLength);
      if (typeName.indexOf('.') == -1) {
        //TODO ignore?
        throw new RuntimeException("Cannot have Simple JSON Schema definitions in the default package");
      }
      current.rootTypeName = typeName;

      Scanner s = null;
      try {
        StringBuilder jsonString = new StringBuilder();
        s = new Scanner(pair.getSecond().toJavaFile());
        while (s.hasNextLine()) {
          jsonString.append(s.nextLine());
        }
        current.content = JSONParser.parseJSON(jsonString.toString());
      } catch (FileNotFoundException e) {
        throw new RuntimeException(e);
      } finally {
        if (s != null) { s.close(); }
      }
      init.add(current);
    }
    return init;
  }

  private static class JsonFile {
    private Object content;
    private String rootTypeName;
  }
}