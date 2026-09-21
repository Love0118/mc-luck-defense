package dev.moma.bootstrap;

import java.net.*;
import java.nio.file.Path;

/** Parent owns Bukkit, dependencies and the bootstrap. Each game generation owns its classes/resources. */
final class RuntimeLoader extends URLClassLoader {
    RuntimeLoader(Path jar,ClassLoader parent)throws MalformedURLException {super(new URL[]{jar.toUri().toURL()},parent);}
    @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException {
        synchronized(getClassLoadingLock(name)) {
            if(!name.startsWith("dev.moma.") || name.startsWith("dev.moma.bootstrap.") || name.equals("dev.moma.paper.MomaPlugin") || name.startsWith("dev.moma.paper.MomaPlugin$"))
                return super.loadClass(name,resolve);
            Class<?> type=findLoadedClass(name);if(type==null)type=findClass(name);
            if(resolve)resolveClass(type);return type;
        }
    }
    @Override public URL getResource(String name){URL own=findResource(name);return own==null?super.getResource(name):own;}
}
