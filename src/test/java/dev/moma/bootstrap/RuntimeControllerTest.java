package dev.moma.bootstrap;

import dev.moma.paper.MomaPlugin;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import javax.tools.ToolProvider;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuntimeControllerTest {
    @TempDir Path dir;
    private final Map<String,Object> view=new HashMap<>();
    private MomaPlugin host;
    private Server server;
    private BukkitScheduler scheduler;
    private final List<Runnable> reboots=new ArrayList<>();
    private Path installed,updates;
    @BeforeEach void setup()throws Exception {
        host=mock(MomaPlugin.class);when(host.getDataFolder()).thenReturn(dir.resolve("data").toFile());
        when(host.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        server=mock(Server.class);scheduler=mock(BukkitScheduler.class);
        when(host.getServer()).thenReturn(server);when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTaskLater(eq(host),any(Runnable.class),eq(100L))).thenAnswer(c->{reboots.add(c.getArgument(1));return null;});
        doAnswer(c->{view.clear();view.put("state",c.getArgument(0));return null;}).when(host).runtimeChanged(any());
        installed=jar("installed.jar","0.16.0","1","base","host","balance","");
        updates=Files.createDirectory(dir.resolve("update"));
    }
    @SuppressWarnings("unchecked") private Map<String,Object> state(){return (Map<String,Object>)view.get("state");}
    private void stage(Path jar)throws Exception{Files.copy(jar,updates.resolve(installed.getFileName()),StandardCopyOption.REPLACE_EXISTING);}
    private void advance(RuntimeController controller){controller.onCommand(mock(CommandSender.class),null,"mud",new String[]{"step"});}
    @Test void switchesCodeKeepsStateAndRollbackUsesCurrentProgressRatherThanOldSnapshot()throws Exception {
        try(var bukkit=mockStatic(Bukkit.class)){bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            try(var controller=new RuntimeController(host,installed,updates)) {
                controller.start();advance(controller);advance(controller);
                stage(jar("next.jar","0.16.1","1","next","host","balance",""));
                assertEquals("0.16.1",controller.check());assertEquals("base",state().get("marker"));assertEquals(2,state().get("counter"));
                controller.reload();assertEquals("next",state().get("marker"));assertEquals(2,state().get("counter"));assertEquals(1,state().get("bindings"));
                assertFalse(Files.exists(updates.resolve(installed.getFileName())));
                advance(controller);controller.reload();assertEquals(3,state().get("counter"));
                controller.rollback();assertEquals("base",state().get("marker"));assertEquals(3,state().get("counter"));
            }
            try(var restarted=new RuntimeController(host,installed,updates)) {
                restarted.start();assertEquals("0.16.0",restarted.version());
            }
        }
    }
    @Test void rejectsHostSchemaBalanceAndCorruptStateBeforeSuspendingOldCode()throws Exception {
        try(var bukkit=mockStatic(Bukkit.class)){bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            try(var controller=new RuntimeController(host,installed,updates)) {
                controller.start();advance(controller);Object original=view.get("state");
                for(String mismatch:List.of("host","schema","balance","state")) {
                    stage(jar(mismatch+".jar","0.16.1",mismatch.equals("schema")?"2":"1","next",mismatch.equals("host")?"other":"host",
                            mismatch.equals("balance")?"other":"balance",mismatch.equals("state")?"reset":""));
                    assertThrows(Exception.class,controller::reload,mismatch);
                    assertSame(original,view.get("state"));assertEquals(1,state().get("counter"));assertEquals(1,state().get("activations"));
                }
                Files.writeString(updates.resolve(installed.getFileName()),"invalid jar");
                assertThrows(Exception.class,controller::reload);assertSame(original,view.get("state"));
            }
        }
    }
    @Test void activationFailureDiscardsPartialBindingsAndReactivatesPreviousRuntime()throws Exception {
        try(var bukkit=mockStatic(Bukkit.class)){bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            try(var controller=new RuntimeController(host,installed,updates)) {
                controller.start();advance(controller);
                stage(jar("fail.jar","0.16.1","1","failure","host","balance","activate"));
                assertThrows(Exception.class,controller::reload);
                assertEquals("base",state().get("marker"));assertEquals(1,state().get("counter"));assertEquals(1,state().get("bindings"));assertEquals(2,state().get("activations"));
                assertFalse(Files.exists(dir.resolve("data/runtime/active.properties")));
                assertFalse(Files.exists(updates.resolve(installed.getFileName())),"Failed candidate cannot be auto-installed on restart");
            }
        }
    }
    @Test void permissionCheckPreventsUpdateAndRuntimeSelectionSurvivesHostRestart()throws Exception {
        try(var bukkit=mockStatic(Bukkit.class)){bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            try(var controller=new RuntimeController(host,installed,updates)) {
                controller.start();stage(jar("next.jar","0.16.1","1","next","host","balance",""));
                controller.onCommand(mock(CommandSender.class),null,"mud",new String[]{"reload"});
                assertEquals("0.16.0",controller.version());controller.reload();
            }
            try(var controller=new RuntimeController(host,installed,updates)){controller.start();assertEquals("0.16.1",controller.version());}
        }
    }
    @Test void failedRuntimeSelectionWriteRollsBackTheAlreadyActivatedCandidate()throws Exception {
        try(var bukkit=mockStatic(Bukkit.class)){bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            try(var controller=new RuntimeController(host,installed,updates)) {
                controller.start();advance(controller);stage(jar("next.jar","0.16.1","1","next","host","balance",""));
                Path blocked=Files.createDirectory(dir.resolve("data/runtime/active.properties"));Files.writeString(blocked.resolve("keep.txt"),"fixture");
                assertThrows(Exception.class,controller::reload);
                assertEquals("base",state().get("marker"));assertEquals(1,state().get("counter"));assertEquals(1,state().get("bindings"));
                assertEquals("0.16.0",controller.version());assertEquals("fixture",Files.readString(blocked.resolve("keep.txt")));
            }
        }
    }
    @Test void replacingThePermanentHostOnDiskTakesPrecedenceOverItsOldRuntimePointer()throws Exception {
        try(var bukkit=mockStatic(Bukkit.class)){bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            try(var controller=new RuntimeController(host,installed,updates)) {
                controller.start();controller.reload();
            }
            Files.copy(jar("new-host.jar","0.16.2","1","new-host","host","balance",""),installed,StandardCopyOption.REPLACE_EXISTING);
            try(var controller=new RuntimeController(host,installed,updates)){controller.start();assertEquals("0.16.2",controller.version());}
        }
    }
    @Test void downloadedUpdateUsesSafeReloadAndRejectsIncompatibleBuildWithoutStagingIt()throws Exception {
        try(var bukkit=mockStatic(Bukkit.class)){bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            try(var controller=new RuntimeController(host,installed,updates)) {
                controller.start();advance(controller);assertFalse(controller.installDownloaded(installed));
                Path next=jar("download.jar","0.17.0","1","download","host","balance","");
                assertTrue(controller.installDownloaded(next));assertEquals("download",state().get("marker"));assertEquals(1,state().get("counter"));
                assertThrows(Exception.class,()->controller.installDownloaded(jar("incompatible.jar","0.18.0","2","bad","other","balance","")));
                assertEquals("0.17.0",controller.version());assertEquals(1,state().get("counter"));
                assertFalse(Files.exists(updates.resolve(installed.getFileName())));
                assertThrows(Exception.class,()->controller.installDownloaded(jar("broken.jar","0.17.1","1","bad","host","balance","activate")));
                assertEquals("download",state().get("marker"));assertEquals(1,state().get("bindings"));assertEquals(1,state().get("counter"));
                controller.rollback();assertEquals("base",state().get("marker"));assertEquals(1,state().get("counter"));
            }
        }
    }
    @Test void explicitForceAcceptsBalanceChangeAndPreservesSessionsThroughRollback()throws Exception {
        try(var bukkit=mockStatic(Bukkit.class)){bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            try(var controller=new RuntimeController(host,installed,updates)) {
                controller.start();advance(controller);advance(controller);
                stage(jar("balance-only.jar","1.0.7","1","next","host","new-balance",""));
                assertThrows(IllegalArgumentException.class,controller::check);
                assertEquals("1.0.7",controller.check(true));
                var admin=mock(CommandSender.class);when(admin.hasPermission("moma.admin")).thenReturn(true);
                controller.onCommand(admin,null,"mud",new String[]{"reload","force"});
                assertEquals("1.0.7",controller.version());assertEquals("next",state().get("marker"));
                assertEquals(2,state().get("counter"));assertFalse(Files.exists(updates.resolve(installed.getFileName())));
                advance(controller);assertEquals(3,state().get("counter"));
                assertThrows(IllegalArgumentException.class,controller::rollback);
                controller.onCommand(admin,null,"mud",new String[]{"rollback","force"});
                assertEquals("0.16.0",controller.version());assertEquals("base",state().get("marker"));
                assertEquals(3,state().get("counter"));
            }
        }
    }
    @Test void forceStillRejectsHostSchemaAndFailedRestoration()throws Exception {
        try(var bukkit=mockStatic(Bukkit.class)){bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            try(var controller=new RuntimeController(host,installed,updates)) {
                controller.start();advance(controller);Object original=view.get("state");
                for(String mismatch:List.of("host","schema","state")) {
                    stage(jar(mismatch+"-forced.jar","1.0.7",mismatch.equals("schema")?"2":"1","next",
                            mismatch.equals("host")?"other":"host","new-balance",mismatch.equals("state")?"reset":""));
                    assertThrows(Exception.class,()->controller.reload(true),mismatch);
                    assertSame(original,view.get("state"));assertEquals(1,state().get("counter"));
                }
            }
        }
    }
    @Test void downloadedForceCanApplyDifferentBalanceWithoutStaging()throws Exception {
        try(var bukkit=mockStatic(Bukkit.class)){bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            try(var controller=new RuntimeController(host,installed,updates)) {
                controller.start();advance(controller);
                Path downloaded=jar("forced-download.jar","1.0.7","1","next","host","new-balance","");
                assertThrows(IllegalArgumentException.class,()->controller.installDownloaded(downloaded));
                assertTrue(controller.installDownloaded(downloaded,true));
                assertEquals("next",state().get("marker"));assertEquals(1,state().get("counter"));
                assertFalse(Files.exists(updates.resolve(installed.getFileName())));
            }
        }
    }
    @Test void restartUpdateKeepsTheRunningGameUntilVerifiedJarIsStagedAndShutdownRuns()throws Exception {
        try(var bukkit=mockStatic(Bukkit.class)){bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            Path next=jar("restart.jar","1.0.44","2","next","new-host","balance","");
            String original=RuntimeArtifact.hash(installed);
            try(var controller=new RuntimeController(host,installed,updates)) {
                controller.start();advance(controller);
                assertThrows(RuntimeController.RestartRequiredException.class,()->controller.installDownloaded(next,true));
                controller.stageForRestart(next);
                assertEquals(original,RuntimeArtifact.hash(installed));
                assertEquals(RuntimeArtifact.hash(next),RuntimeArtifact.hash(updates.resolve(installed.getFileName())));
                assertTrue(Files.exists(dir.resolve("data/runtime/cache/"+original+".jar")));
                assertEquals("base",state().get("marker"));assertEquals(1,state().get("counter"));assertEquals(1,state().get("bindings"));
                assertEquals(1,reboots.size());verify(server,never()).shutdown();
                assertThrows(IllegalStateException.class,()->controller.check(true));
                assertThrows(IllegalStateException.class,()->controller.reload(true));
                assertThrows(IllegalStateException.class,()->controller.stageForRestart(next));
                reboots.getFirst().run();verify(server,times(1)).shutdown();
            }
            Files.move(updates.resolve(installed.getFileName()),installed,StandardCopyOption.REPLACE_EXISTING);
            try(var restarted=new RuntimeController(host,installed,updates)) {
                restarted.start();assertEquals("1.0.44",restarted.version());assertEquals("next",state().get("marker"));
            }
        }
    }
    @Test void restartUpdateRejectsConflictingAndInvalidFilesWithoutSchedulingShutdown()throws Exception {
        try(var bukkit=mockStatic(Bukkit.class)){bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            try(var controller=new RuntimeController(host,installed,updates)) {
                controller.start();advance(controller);
                Path next=jar("restart.jar","1.0.44","1","next","new-host","balance","");
                Path staged=Files.writeString(updates.resolve(installed.getFileName()),"other update");
                assertThrows(IllegalStateException.class,()->controller.stageForRestart(next));
                assertEquals("other update",Files.readString(staged));
                assertThrows(Exception.class,()->controller.stageForRestart(Files.writeString(dir.resolve("invalid.jar"),"bad jar")));
                assertEquals(1,state().get("counter"));assertTrue(reboots.isEmpty());verify(server,never()).shutdown();
            }
        }
    }
    @Test void restartSchedulingFailureRemovesOnlyItsOwnStageAndCanRetry()throws Exception {
        try(var bukkit=mockStatic(Bukkit.class)){bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            try(var controller=new RuntimeController(host,installed,updates)) {
                controller.start();Path next=jar("restart.jar","1.0.44","1","next","new-host","balance","");
                when(scheduler.runTaskLater(eq(host),any(Runnable.class),eq(100L)))
                        .thenThrow(new IllegalStateException("scheduler unavailable"))
                        .thenAnswer(c->{reboots.add(c.getArgument(1));return null;});
                assertThrows(IllegalStateException.class,()->controller.stageForRestart(next));
                assertFalse(Files.exists(updates.resolve(installed.getFileName())));assertTrue(reboots.isEmpty());
                controller.stageForRestart(next);assertEquals(1,reboots.size());verify(server,never()).shutdown();
            }
        }
    }
    @Test void restartSchedulingFailurePreservesAnAlreadyStagedIdenticalUpdate()throws Exception {
        try(var bukkit=mockStatic(Bukkit.class)){bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            try(var controller=new RuntimeController(host,installed,updates)) {
                controller.start();Path next=jar("restart.jar","1.0.44","1","next","new-host","balance","");stage(next);
                when(scheduler.runTaskLater(eq(host),any(Runnable.class),eq(100L))).thenThrow(new IllegalStateException("scheduler unavailable"));
                assertThrows(IllegalStateException.class,()->controller.stageForRestart(next));
                assertEquals(RuntimeArtifact.hash(next),RuntimeArtifact.hash(updates.resolve(installed.getFileName())));
                verify(server,never()).shutdown();
            }
        }
    }
    @Test void changedStagedFileCancelsRestartBeforeShutdown()throws Exception {
        try(var bukkit=mockStatic(Bukkit.class)){bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            try(var controller=new RuntimeController(host,installed,updates)) {
                controller.start();Path next=jar("restart.jar","1.0.44","1","next","new-host","balance","");
                controller.stageForRestart(next);
                Path staged=updates.resolve(installed.getFileName());Files.writeString(staged,"changed");
                reboots.getFirst().run();verify(server,never()).shutdown();
                assertEquals("base",state().get("marker"));assertEquals("changed",Files.readString(staged));
                Files.delete(staged);assertEquals("0.16.0",controller.check());
            }
        }
    }
    private Path jar(String file,String version,String schema,String marker,String hostMarker,String balance,String fault)throws Exception {
        Path work=Files.createTempDirectory(dir,"compile-"),source=work.resolve("GameRuntime.java");
        String code="""
                package dev.moma.paper;
                import dev.moma.bootstrap.GameModule;
                import org.bukkit.command.*;
                import java.util.*;
                public final class GameRuntime implements GameModule {
                  private final Map<String,Object> state=new HashMap<>();
                  public void prepare(MomaPlugin host,byte[] snapshot) {
                    int n=snapshot==null?0:Integer.parseInt(new String(snapshot));
                    state.put("counter",FAULT.equals("reset")?0:n);state.put("marker",MARKER);state.put("activations",0);
                  }
                  private static final String MARKER="%s",FAULT="%s";
                  public void activate(boolean initial) {
                    state.put("bindings",1);state.put("activations",(int)state.get("activations")+1);
                    if(!initial && FAULT.equals("activate"))throw new IllegalStateException("activation failed");
                  }
                  public void checkReloadReady(){}
                  public byte[] snapshot(){return state.get("counter").toString().getBytes();}
                  public String fingerprint(){return state.get("counter").toString();}
                  public boolean hasSessions(){return true;}
                  public void suspend(){state.put("bindings",0);}
                  public void discard(){state.put("bindings",0);}
                  public void shutdown(){state.put("bindings",0);}
                  public Object diagnosticState(){return state;}
                  public boolean onCommand(CommandSender s,Command c,String label,String[] a){state.put("counter",(int)state.get("counter")+1);return true;}
                  public List<String> onTabComplete(CommandSender s,Command c,String label,String[] a){return List.of();}
                }
                """.formatted(marker,fault);
        Files.writeString(source,code);
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"-classpath",System.getProperty("java.class.path"),"-d",work.toString(),source.toString()));
        Path jar=dir.resolve(file);
        try(var out=new JarOutputStream(Files.newOutputStream(jar))) {
            entry(out,"dev/moma/paper/GameRuntime.class",Files.readAllBytes(work.resolve("dev/moma/paper/GameRuntime.class")));
            entry(out,"mud-runtime.properties",("host-api=1\nstate-schema="+schema+"\nversion="+version+"\n").getBytes());
            entry(out,"plugin.yml",("name: MCLuckDefense\nmain: dev.moma.paper.MomaPlugin\nversion: '"+version+"'\n").getBytes());
            entry(out,"host-marker.txt",hostMarker.getBytes());entry(out,"campaign.properties",balance.getBytes());
        }
        return jar;
    }
    private static void entry(JarOutputStream out,String name,byte[] bytes)throws Exception {
        out.putNextEntry(new JarEntry(name));out.write(bytes);out.closeEntry();
    }
}
