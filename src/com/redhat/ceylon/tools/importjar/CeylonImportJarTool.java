/*
 * Copyright Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the authors tag. All rights reserved.
 *
 * This copyrighted material is made available to anyone wishing to use,
 * modify, copy, or redistribute it subject to the terms and conditions
 * of the GNU General Public License version 2.
 * 
 * This particular file is subject to the "Classpath" exception as provided in the 
 * LICENSE file that accompanied this code.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT A
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License,
 * along with this distribution; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA  02110-1301, USA.
 */
package com.redhat.ceylon.tools.importjar;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import com.redhat.ceylon.cmr.api.ArtifactContext;
import com.redhat.ceylon.cmr.api.JDKUtils;
import com.redhat.ceylon.cmr.api.ModuleDependencyInfo;
import com.redhat.ceylon.cmr.api.ModuleInfo;
import com.redhat.ceylon.cmr.api.ModuleQuery;
import com.redhat.ceylon.cmr.api.ModuleSearchResult;
import com.redhat.ceylon.cmr.api.ModuleSearchResult.ModuleDetails;
import com.redhat.ceylon.cmr.api.ModuleVersionQuery;
import com.redhat.ceylon.cmr.api.RepositoryManager;
import com.redhat.ceylon.cmr.ceylon.OutputRepoUsingTool;
import com.redhat.ceylon.cmr.impl.CMRException;
import com.redhat.ceylon.cmr.impl.JarUtils;
import com.redhat.ceylon.cmr.impl.PropertiesDependencyResolver;
import com.redhat.ceylon.cmr.impl.XmlDependencyResolver;
import com.redhat.ceylon.common.Messages;
import com.redhat.ceylon.common.ModuleUtil;
import com.redhat.ceylon.common.Versions;
import com.redhat.ceylon.common.tool.Argument;
import com.redhat.ceylon.common.tool.Description;
import com.redhat.ceylon.common.tool.Option;
import com.redhat.ceylon.common.tool.OptionArgument;
import com.redhat.ceylon.common.tool.RemainingSections;
import com.redhat.ceylon.common.tool.Summary;
import com.redhat.ceylon.common.tool.ToolUsageError;
import com.redhat.ceylon.common.tools.CeylonTool;
import com.redhat.ceylon.common.tools.ModuleSpec;

@Summary("Imports a jar file into a Ceylon module repository")
@Description("Imports the given `<jar-file>` using the module name and version " +
        "given by `<module>` into the repository named by the " +
        "`--out` option.\n" +
        "\n" +
        "`<module>` is a module name and version separated with a slash, for example " +
        "`com.example.foobar/1.2.0`.\n" +
        "\n" +
        "`<jar-file>` is the name of the Jar file to import.")
@RemainingSections(OutputRepoUsingTool.DOCSECTION_REPOSITORIES)
public class CeylonImportJarTool extends OutputRepoUsingTool {

    private ModuleSpec module;
    private File jarFile;
    private File descriptor;
    private boolean updateDescriptor;
    private boolean force;
    private boolean dryRun;
    private boolean showClasses;
    private boolean showSuggestions;
    
    private Set<String> jarClassNames;
    private Set<Type> checkedTypes;
    private boolean hasErrors;
    private boolean hasProblems;

    public CeylonImportJarTool() {
        super(ImportJarMessages.RESOURCE_BUNDLE);
    }
    
    @OptionArgument(argumentName="file")
    @Description("Specify a module.xml or module.properties file to be used "
            + "as the module descriptor")
    public void setDescriptor(File descriptor) {
        this.descriptor = descriptor;
    }
    
    @Argument(argumentName="module", multiplicity="1", order=0)
    public void setModuleSpec(String module) {
        setModuleSpec(ModuleSpec.parse(module, 
                ModuleSpec.Option.VERSION_REQUIRED, 
                ModuleSpec.Option.DEFAULT_MODULE_PROHIBITED));
    }
    
    public void setModuleSpec(ModuleSpec module) {
        this.module = module;
    }

    @Argument(argumentName="jar-file", multiplicity="1", order=1)
    public void setFile(File jarFile) {
        this.jarFile = jarFile;
    }
    
    @Option(longName="update-descriptor")
    @Description("Whenever possible will create or adjust the descriptor file with the necessary definitions.")
    public void setUpdateDescriptor(boolean updateDescriptor) {
        this.updateDescriptor = updateDescriptor;
    }
    
    @Option(longName="force")
    @Description("Skips sanity checks and forces publication of the JAR.")
    public void setForce(boolean force) {
        this.force = force;
    }
    
    @Option(longName="dry-run")
    @Description("Performs all the sanity checks but does not publish the JAR.")
    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }
    
    @Option(longName="show-classes")
    @Description("Shows all external classes that are not declared as imports instead of their packages only.")
    public void setShowClasses(boolean showClasses) {
        this.showClasses = showClasses;
    }
    
    @Option(longName="show-suggestions")
    @Description("Shows suggestions for modules based on missing package names (this can take a long time).")
    public void setShowSuggestions(boolean showSuggestions) {
        this.showSuggestions = showSuggestions;
    }
    
    @Override
    protected boolean needsSystemRepo() {
        return false;
    }

    @Override
    public void initialize(CeylonTool mainTool) {
        setSystemProperties();
        File f = applyCwd(jarFile);
        checkReadableFile(f, "error.jarFile", true);
        if(!f.getName().toLowerCase().endsWith(".jar"))
            throw new ImportJarException("error.jarFile.notJar", new Object[]{f.toString()}, null);
        
        if (descriptor == null) {
            String baseName = f.getName().substring(0, f.getName().length() - 4);
            File desc = new File(f.getParentFile(), baseName + ".module.xml");
            if (!desc.isFile()) {
                desc = new File(f.getParentFile(), baseName + ".module.properties");
                if (desc.isFile() || updateDescriptor) {
                    descriptor = desc;
                }
            } else {
                descriptor = desc;
            }
        }
        if (descriptor != null) {
            checkReadableFile(applyCwd(descriptor), "error.descriptorFile", !updateDescriptor);
            if(!(descriptor.toString().toLowerCase().endsWith(".xml") ||
                    descriptor.toString().toLowerCase().endsWith(".properties")))
                throw new ImportJarException("error.descriptorFile.badSuffix", new Object[]{descriptor}, null);
        }
    }

    private void checkReadableFile(File f, String keyPrefix, boolean required) {
        if (f.exists()) {
            if(f.isDirectory())
                throw new ImportJarException(keyPrefix + ".isDirectory", new Object[]{f.toString()}, null);
            if(!f.canRead())
                throw new ImportJarException(keyPrefix + ".notReadable", new Object[]{f.toString()}, null);
        } else if (required) {
            throw new ImportJarException(keyPrefix + ".doesNotExist", new Object[]{f.toString()}, null);
        }
    }
    
    // Check the public API for the JAR we're importing and report any problems that are found
    private void checkPublicApi(Set<ModuleDependencyInfo> expectedDependencies) throws IOException {
        Set<String> externalClasses = gatherExternalClasses();
        
        if (descriptor != null) {
            File descriptorFile = applyCwd(descriptor);
            if (descriptorFile.exists()) {
                if (descriptor.toString().toLowerCase().endsWith(".xml")) {
                    checkModuleXml(descriptorFile, externalClasses, expectedDependencies);
                } else if(descriptor.toString().toLowerCase().endsWith(".properties")) {
                    checkModuleProperties(descriptorFile, externalClasses, expectedDependencies);
                }
            }
        }
        
        if (!externalClasses.isEmpty()) {
            if (!showClasses) {
                Set<String> externalPackages = getPackagesFromClasses(externalClasses);
                if (!externalPackages.isEmpty()) {
                    Set<String> jdkModules = gatherJdkModules(externalPackages);
                    if (!jdkModules.isEmpty()) {
                        msg("info.declare.jdk.imports").newline();
                        for (String mod : jdkModules) {
                            append("    ").append(mod).newline();
                            expectedDependencies.add(new ModuleDependencyInfo(mod, JDKUtils.jdk.version, false, true));
                        }
                        hasProblems = true;
                    }
                    if (!externalPackages.isEmpty()) {
                        msg("info.declare.module.imports").newline();
                        if (!showSuggestions) {
                            msg("info.try.suggestions").newline();
                        }
                        for (String pkg : externalPackages) {
                            append("    ").append(pkg);
                            if (showSuggestions) {
                                outputSuggestions(pkg, expectedDependencies);
                            }
                            newline();
                        }
                        hasErrors = true;
                    }
                }
                Set<String> externalDefaultClasses = getDefaultPackageClasses(externalClasses);
                if (!externalDefaultClasses.isEmpty()) {
                    msg("info.declare.class.imports").newline();
                    for (String cls : externalDefaultClasses) {
                        append("    ").append(cls).newline();
                    }
                    hasErrors = true;
                }
            } else {
                msg("info.declare.class.imports").newline();
                for (String cls : externalClasses) {
                    append("    ").append(cls).newline();
                }
                hasErrors = true;
            }
        }
    }

    // Return the set of class names for those types referenced by the
    // public API of the classes in the JAR we're importing and that are
    // not part of the JAR itself
    private Set<String> gatherExternalClasses() {
        checkedTypes = new HashSet<>();
        HashSet<String> externalClasses = new HashSet<>();
        try {
            File jar = applyCwd(jarFile);
            URLClassLoader cl = new URLClassLoader(new URL[] { jar.toURI().toURL() });
            try {
                jarClassNames = JarUtils.gatherClassnamesFromJar(jar);
                for (String className : jarClassNames) {
                    Class<?> clazz;
                    try {
                        clazz = cl.loadClass(className);
                    } catch (NoClassDefFoundError | ClassNotFoundException e) {
                        handleNotFoundErrors(externalClasses, e);
                        continue;
                    }
                    checkPublicApi(externalClasses, clazz);
                }
            } finally {
                cl.close();
            }
        } catch (IOException e) {
            throw new ImportJarException("error.jarFile.unableToAnalyze", e);
        }
        return externalClasses;
    }
    
    private void outputSuggestions(String pkg, Set<ModuleDependencyInfo> expectedDependencies) throws IOException {
        flush();
        ModuleDependencyInfo dep = null;
        Set<ModuleDetails> suggestions = findSuggestions(pkg);
        if (!suggestions.isEmpty()) {
            append(", ");
            if (suggestions.size() > 1) {
                msg("info.try.importing.multiple");
                for (ModuleDetails md : suggestions) {
                    newline();
                    String modver = md.getName() + "/" + md.getLastVersion().getVersion();
                    append("        ").append(modver);
                    dep = new ModuleDependencyInfo(md.getName(), md.getLastVersion().getVersion(), false, true);
                }
            } else {
                ModuleDetails md = suggestions.iterator().next();
                String modver = md.getName() + "/" + md.getLastVersion().getVersion();
                msg("info.try.importing", modver);
                dep = new ModuleDependencyInfo(md.getName(), md.getLastVersion().getVersion(), false, true);
            }
            if (dep != null) {
                expectedDependencies.add(dep);
                hasProblems = true;
            }
        }
    }

    private Set<ModuleDetails> findSuggestions(String pkg) {
        Set<ModuleDetails> suggestions = new TreeSet<>();
        ModuleVersionQuery query = new ModuleVersionQuery("", null, ModuleQuery.Type.JVM);
        query.setBinaryMajor(Versions.JVM_BINARY_MAJOR_VERSION);
        query.setBinaryMinor(Versions.JVM_BINARY_MINOR_VERSION);
        query.setMemberName(pkg);
        query.setMemberSearchExact(true);
        query.setMemberSearchPackageOnly(true);
        ModuleSearchResult result = getRepositoryManager().completeModules(query);
        for (ModuleDetails mvd : result.getResults()) {
            suggestions.add(mvd);
        }
        return suggestions;
    }

    // Check the public API of a class for types that are external to the JAR we're importing
    private void checkPublicApi(Set<String> externalClasses, Class<?> clazz) {
        if (clazz.getModifiers() != Modifier.PUBLIC) {
            // Not interested in any but public classes
            return;
        }
        // Make sure we get an actual class, not an array
        while (clazz.isArray()) {
            clazz = clazz.getComponentType();
        }
        try {
            checkTypes(externalClasses, clazz.getTypeParameters());
        } catch (NoClassDefFoundError | TypeNotPresentException e) {
            handleNotFoundErrors(externalClasses, e);
        }
        try {
            checkAnnotations(externalClasses, clazz.getAnnotations());
        } catch (NoClassDefFoundError | TypeNotPresentException e) {
            handleNotFoundErrors(externalClasses, e);
        }
        try {
            checkType(externalClasses, clazz.getGenericSuperclass());
        } catch (NoClassDefFoundError | TypeNotPresentException e) {
            handleNotFoundErrors(externalClasses, e);
        }
        try {
            Type[] interfaces = clazz.getGenericInterfaces();
            for (Type iface : interfaces) {
                checkType(externalClasses, iface);
            }
        } catch (NoClassDefFoundError | TypeNotPresentException e) {
            handleNotFoundErrors(externalClasses, e);
        }
        try {
            Method[] methods = clazz.getMethods();
            for (Method mth : methods) {
                if ((mth.getModifiers() & (Modifier.PUBLIC | Modifier.PROTECTED)) != 0) {
                    checkTypes(externalClasses, mth.getTypeParameters());
                    checkAnnotations(externalClasses, mth.getAnnotations());
                    checkType(externalClasses, mth.getGenericReturnType());
                    for (Type param : mth.getGenericParameterTypes()) {
                        checkType(externalClasses, param);
                    }
                    checkAnnotations(externalClasses, mth.getParameterAnnotations());
                    for (Type ex : mth.getGenericExceptionTypes()) {
                        checkType(externalClasses, ex);
                    }
                }
            }
        } catch (NoClassDefFoundError | TypeNotPresentException e) {
            handleNotFoundErrors(externalClasses, e);
        }
        try {
            Field[] fields = clazz.getFields();
            for (Field fld : fields) {
                if ((fld.getModifiers() & (Modifier.PUBLIC | Modifier.PROTECTED)) != 0) {
                    checkAnnotations(externalClasses, fld.getAnnotations());
                    checkType(externalClasses, fld.getGenericType());
                }
            }
        } catch (NoClassDefFoundError | TypeNotPresentException e) {
            handleNotFoundErrors(externalClasses, e);
        }
        try {
            Constructor<?>[] constructors = clazz.getConstructors();
            for (Constructor<?> cons : constructors) {
                if ((cons.getModifiers() & (Modifier.PUBLIC | Modifier.PROTECTED)) != 0) {
                    checkTypes(externalClasses, cons.getTypeParameters());
                    checkAnnotations(externalClasses, cons.getAnnotations());
                    for (Type param : cons.getGenericParameterTypes()) {
                        checkType(externalClasses, param);
                    }
                    checkAnnotations(externalClasses, cons.getParameterAnnotations());
                    for (Type ex : cons.getGenericExceptionTypes()) {
                        checkType(externalClasses, ex);
                    }
                }
            }
        } catch (NoClassDefFoundError | TypeNotPresentException e) {
            handleNotFoundErrors(externalClasses, e);
        }
    }
    
    private void checkAnnotations(Set<String> externalClasses, Annotation[][] annotations) {
        for (Annotation[] annos : annotations) {
            checkAnnotations(externalClasses, annos);
        }
    }

    private void checkAnnotations(Set<String> externalClasses, Annotation[] annotations) {
        for (Annotation anno : annotations) {
            checkType(externalClasses, anno.annotationType());
        }
    }

    private void checkTypes(Set<String> externalClasses, Type[] types) {
        for (Type t : types) {
            checkType(externalClasses, t);
        }
    }
    
    // Check if the type is external to the JAR we're importing, if so add it to the given set
    private void checkType(Set<String> externalClasses, Type type) {
        if (type != null) {
            if (checkedTypes.contains(type)) {
                return;
            }
            checkedTypes.add(type);
            if (type instanceof Class) {
                checkClass(externalClasses, (Class<?>)type);
            } else if (type instanceof GenericArrayType) {
                checkType(externalClasses, ((GenericArrayType) type).getGenericComponentType());
            } else if (type instanceof ParameterizedType) {
                checkType(externalClasses, ((ParameterizedType) type).getOwnerType());
                for (Type t : ((ParameterizedType) type).getActualTypeArguments()) {
                    checkType(externalClasses, t);
                }
            } else if (type instanceof TypeVariable) {
                Type[] bounds;
                try {
                    bounds = ((TypeVariable<?>) type).getBounds();
                } catch (NoClassDefFoundError | TypeNotPresentException e) {
                    handleNotFoundErrors(externalClasses, e);
                    return;
                }
                for (Type b : bounds) {
                    checkType(externalClasses, b);
                }
            } else if (type instanceof WildcardType) {
                Type[] lower;
                try {
                    lower = ((WildcardType) type).getLowerBounds();
                } catch (NoClassDefFoundError | TypeNotPresentException e) {
                    handleNotFoundErrors(externalClasses, e);
                    return;
                }
                for (Type b : lower) {
                    checkType(externalClasses, b);
                }
                Type[] upper;
                try {
                    upper = ((WildcardType) type).getUpperBounds();
                } catch (NoClassDefFoundError | TypeNotPresentException e) {
                    handleNotFoundErrors(externalClasses, e);
                    return;
                }
                for (Type b : upper) {
                    checkType(externalClasses, b);
                }
            } else {
                System.err.println("Handling of type not implemented for " + type.getClass().getName());
            }
        }
    }

    // Check if the class is external to the JAR we're importing, if so add it to the given set
    private void checkClass(Set<String> externalClasses, Class<?> clazz) {
        // Make sure we get an actual class, not an array
        while (clazz.isArray()) {
            clazz = clazz.getComponentType();
        }
        if (clazz.isPrimitive()) {
            // No need to check primitives
            return;
        }
        String name = clazz.getName();
        if (jarClassNames.contains(name)) {
            // Internal to the JAR so it's okay
            return;
        }
        // FIXME Do we need to do more?
        externalClasses.add(name);
    }

    // Extract the name of the class that couldn't be loaded and add it to the given set
    private void handleNotFoundErrors(Set<String> notFound, Throwable orgth) {
        // This is very brittle because it depends on the message only containing the class name
        Throwable th = orgth;
        if (th instanceof TypeNotPresentException
                && (th.getCause() instanceof ClassNotFoundException
                        || th.getCause() instanceof NoClassDefFoundError)) {
            th = th.getCause();
        }
        String name = th.getMessage().replace('/', '.');
        if (name.startsWith("L") && name.endsWith(";")) {
            name = name.substring(1, name.length() - 1);
        }
        if (notFound.add(name)) {
            log.debug("NOT FOUND " + name);
        }
    }
    
    // Given a set of class names return the set of their package names
    // (excluding those classes that aren't in any packages)
    private Set<String> getPackagesFromClasses(Set<String> classes) {
        Set<String> packages = new TreeSet<>();
        for (String className : classes) {
            String pkg = getPackageFromClass(className);
            if (!pkg.isEmpty()) {
                packages.add(pkg);
            }
        }
        return packages;
    }

    // Given a fully qualified class name return it's package
    // (or an empty string if it's not part of any package)
    private String getPackageFromClass(String className) {
        int p = className.lastIndexOf('.');
        if (p >= 0) {
            return className.substring(0, p);
        } else {
            return "";
        }
    }
    
    // Given a set of class names returns the set of those that aren't in any package
    private Set<String> getDefaultPackageClasses(Set<String> classes) {
        Set<String> defclasses = new TreeSet<>();
        for (String className : classes) {
            int p = className.lastIndexOf('.');
            if (p < 0) {
                defclasses.add(className);
            }
        }
        return defclasses;
    }
    
    // From a list of package names we extract the ones that
    // belong to a JDK module (removing them from the original
    // list) and we return the list of JDK modules we found
    private Set<String> gatherJdkModules(Set<String> packages) {
        Set<String> jdkModules = new TreeSet<>();
        Set<String> newPackages = new HashSet<>();
        for (String pkg : packages) {
            String mod = JDKUtils.getJDKModuleNameForPackage(pkg);
            if (mod != null) {
                jdkModules.add(mod);
            } else {
                newPackages.add(pkg);
            }
        }
        packages.clear();
        packages.addAll(newPackages);
        return jdkModules;
    }

    // Publish the JAR to the specified or default output repository
    public void publish() {
        RepositoryManager outputRepository = getOutputRepositoryManager();

        ArtifactContext context = new ArtifactContext(module.getName(), module.getVersion(), ArtifactContext.JAR);
        context.setForceOperation(true);
        ArtifactContext descriptorContext = null;
        if (descriptor != null) {
            if (descriptor.toString().toLowerCase().endsWith(".xml")) {
                descriptorContext = new ArtifactContext(module.getName(), module.getVersion(), ArtifactContext.MODULE_XML);
            } else if (descriptor.toString().toLowerCase().endsWith(".properties")) {
                descriptorContext = new ArtifactContext(module.getName(), module.getVersion(), ArtifactContext.MODULE_PROPERTIES);
            }
            descriptorContext.setForceOperation(true);
        }
        try{
            File jarFile = applyCwd(this.jarFile);
            outputRepository.putArtifact(context, jarFile);
            signArtifact(context, jarFile);
            
            if (descriptorContext != null) {
                outputRepository.putArtifact(descriptorContext, applyCwd(descriptor));
            }
        }catch(CMRException x){
            throw new ImportJarException("error.failedWriteArtifact", new Object[]{context, x.getLocalizedMessage()}, x);
        }catch(Exception x){
            // FIXME: remove when the whole CMR is using CMRException
            throw new ImportJarException("error.failedWriteArtifact", new Object[]{context, x.getLocalizedMessage()}, x);
        }
    }
    
    // Check the properties descriptor file for problems and at the same time
    // remove all classes that are found within the imported modules
    // from the given set of external class names
    private void checkModuleProperties(File descriptorFile, Set<String> externalClasses, Set<ModuleDependencyInfo> expectedDependencies) throws IOException {
        try{
            ModuleInfo dependencies = PropertiesDependencyResolver.INSTANCE.resolveFromFile(descriptorFile);
            checkDependencies(dependencies, externalClasses, expectedDependencies);
        }catch(ImportJarException x){
            throw x;
        }catch(IOException x){
            throw x;
        }catch(Exception x){
            throw new ImportJarException("error.descriptorFile.invalid.properties", new Object[]{descriptorFile.getPath()}, x);
        }
    }

    // Check the XML descriptor file for problems and at the same time
    // remove all classes that are found within the imported modules
    // from the given set of external class names
    private void checkModuleXml(File descriptorFile, Set<String> externalClasses, Set<ModuleDependencyInfo> expectedDependencies) throws IOException {
        try{
            ModuleInfo dependencies = XmlDependencyResolver.INSTANCE.resolveFromFile(descriptorFile);
            checkDependencies(dependencies, externalClasses, expectedDependencies);
        }catch(ImportJarException x){
            throw x;
        }catch(IOException x){
            throw x;
        }catch(Exception x){
            throw new ImportJarException("error.descriptorFile.invalid.xml", new Object[]{descriptorFile.getPath(), x.getMessage()}, x);
        }
    }

    // Check the given dependencies for problems and at the same time
    // remove all classes that are found within the imported modules
    // from the given set of external class names
    private void checkDependencies(ModuleInfo dependencies, Set<String> externalClasses, Set<ModuleDependencyInfo> expectedDependencies) throws IOException {
        if (!dependencies.getDependencies().isEmpty()) {
            msg("info.checkingDependencies").newline();
            TreeSet<ModuleDependencyInfo> sortedDeps = new TreeSet<>(dependencies.getDependencies());
            for (ModuleDependencyInfo dep : sortedDeps) {
                String name = dep.getName();
                String version = dep.getVersion();
                // missing dep is OK, it can be fixed later, but invalid module/dependency is not OK
                if(name == null || name.isEmpty())
                    throw new ImportJarException("error.descriptorFile.invalid.module", new Object[]{name}, null);
                if(ModuleUtil.isDefaultModule(name))
                    throw new ImportJarException("error.descriptorFile.invalid.module.default");
                if(version == null || version.isEmpty())
                    throw new ImportJarException("error.descriptorFile.invalid.module.version", new Object[]{version}, null);
                append("    ").append(dep).append(" ... [");
                if (JDKUtils.isJDKModule(name) || JDKUtils.isOracleJDKModule(name)) {
                    if (removeMatchingJdkClasses(externalClasses, name)) {
                        if (dep.isExport()) {
                            msg("info.ok");
                        } else {
                            msg("error.markShared");
                            dep = new ModuleDependencyInfo(dep.getName(), dep.getVersion(), dep.isOptional(), true);
                            hasProblems = true;
                        }
                    } else {
                        if (dep.isExport()) {
                            msg("info.okButUnused");
                            dep = new ModuleDependencyInfo(dep.getName(), dep.getVersion(), dep.isOptional(), false);
                        } else {
                            msg("info.ok");
                        }
                    }
                } else {
                    ArtifactContext context = new ArtifactContext(name, dep.getVersion(), ArtifactContext.CAR, ArtifactContext.JAR);
                    File artifact = getRepositoryManager().getArtifact(context);
                    if (artifact != null && artifact.exists()) {
                        try {
                            Set<String> importedClasses = JarUtils.gatherClassnamesFromJar(artifact);
                            if (removeMatchingClasses(externalClasses, importedClasses)) {
                                if (dep.isExport()) {
                                    msg("info.ok");
                                } else {
                                    msg("error.markShared");
                                    dep = new ModuleDependencyInfo(dep.getName(), dep.getVersion(), dep.isOptional(), true);
                                    hasProblems = true;
                                }
                            } else {
                                if (dep.isExport()) {
                                    msg("info.okButUnused");
                                    dep = new ModuleDependencyInfo(dep.getName(), dep.getVersion(), dep.isOptional(), false);
                                } else {
                                    msg("info.ok");
                                }
                            }
                        } catch (IOException e) {
                            msg("error.checkFailed");
                            hasErrors = true;
                        }
                    } else {
                        msg("error.notFound");
                        hasErrors = true;
                    }
                }
                append("]").newline();
                expectedDependencies.add(dep);
            }
        }
    }

    // Remove all classes that are found within the given set of
    // imported classes from the given set of external classes
    private boolean removeMatchingClasses(Set<String> externalClasses, Set<String> importedClasses) {
        boolean matchesFound = false;
        for (String className : importedClasses) {
            matchesFound |= externalClasses.remove(className);
        }
        return matchesFound;
    }

    // Remove all classes that are part of the given JDK module
    // from the given set of external classes
    private boolean removeMatchingJdkClasses(Set<String> externalClasses, String jdkModule) {
        Set<String> toBeRemoved = new HashSet<>();
        for (String className : externalClasses) {
            String pkgName = getPackageFromClass(className);
            if (JDKUtils.isJDKPackage(jdkModule, pkgName) || JDKUtils.isOracleJDKPackage(jdkModule, pkgName)) {
                toBeRemoved.add(className);
            }
        }
        externalClasses.removeAll(toBeRemoved);
        return !toBeRemoved.isEmpty();
    }

    @Override
    public void run() throws IOException {
        Set<ModuleDependencyInfo> expectedDependencies = new TreeSet<ModuleDependencyInfo>();        
        if (!force || updateDescriptor) {
            checkPublicApi(expectedDependencies);
        }
        if (hasProblems) {
            if (updateDescriptor && descriptor != null) {
                if (!dryRun) {
                    File descriptorFile = applyCwd(descriptor);
                    if (descriptor.toString().toLowerCase().endsWith(".xml")) {
                        updateDescriptorXml(expectedDependencies, descriptorFile);
                    } else if(descriptor.toString().toLowerCase().endsWith(".properties")) {
                        updateDescriptorProperties(expectedDependencies, descriptorFile);
                    }
                }
            } else {
                hasErrors = true;
            }
        }
        if (!hasErrors || force) {
            if (!hasErrors) {
                if (force && !updateDescriptor) {
                    msg("info.forcedUpdate");
                } else {
                    msg("info.noProblems");
                }
            } else {
                msg("error.problemsFoundForced");
            }
            if (!dryRun) {
                msg("info.noProblems.publishing").newline();
                publish();
                String repoString = this.getOutputRepositoryManager().getRepositoriesDisplayString().toString();
                
                msg("info.published", this.module.toString(), repoString.substring(1, repoString.length()-1));
            }
            append(".").newline();
        } else {
            throw new ToolUsageError(Messages.msg(ImportJarMessages.RESOURCE_BUNDLE, "error.problemsFound"));
        }
    }

    private void updateDescriptorProperties(Set<ModuleDependencyInfo> expectedDependencies, File descriptorFile) throws IOException {
        Properties deps = new Properties();
        for (ModuleDependencyInfo mdi : expectedDependencies) {
            String key = mdi.getName();
            String val = mdi.getVersion();
            if (mdi.isExport()) {
                key = "+" + key;
            }
            if (mdi.isOptional()) {
                key = key + "?";
            }
            deps.setProperty(key, val);
        }
        try (OutputStreamWriter out = new OutputStreamWriter(new FileOutputStream(descriptorFile), "UTF-8")) {
            deps.store(out, "Generated by 'ceylon import-jar'");
        }
    }

    private void updateDescriptorXml(Set<ModuleDependencyInfo> expectedDependencies, File descriptorFile) throws IOException {
        append("Updating of XML module descriptor not yet implemented.").newline();
        hasErrors = true;
    }
}