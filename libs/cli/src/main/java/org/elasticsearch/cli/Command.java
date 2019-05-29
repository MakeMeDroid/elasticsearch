/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.cli;

import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;

import java.io.Closeable;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;

/**
 * An action to execute within a cli.
 * 
 * 用于在命令行中执行的命令，比如用于启动ES程序的命令。
 * 
 * 这个类是个便于编写可以从命令行启动的类的工具，提供
 * 自动对命令行参数进行解析的功能。
 * 
 */
public abstract class Command implements Closeable {

    /** A description of the command, used in the help output. */
    protected final String description;

    /**
     * 执行程序前可以通过这个函数做一些初始化动作
     */
    private final Runnable beforeMain;

    /** The option parser for this command. 
     * 
     * 命令行参数解析器
     * 
     */
    protected final OptionParser parser = new OptionParser();

    private final OptionSpec<Void> helpOption = parser.acceptsAll(Arrays.asList("h", "help"), "show help").forHelp();
    private final OptionSpec<Void> silentOption = parser.acceptsAll(Arrays.asList("s", "silent"), "show minimal output");
    private final OptionSpec<Void> verboseOption =
        parser.acceptsAll(Arrays.asList("v", "verbose"), "show verbose output").availableUnless(silentOption);

    /**
     * Construct the command with the specified command description and runnable to execute before main is invoked.
     *
     * @param description the command description
     * @param beforeMain the before-main runnable
     */
    public Command(final String description, final Runnable beforeMain) {
        this.description = description;
        this.beforeMain = beforeMain;
    }

    private Thread shutdownHookThread;

    /** Parses options for this command from args and executes it. */
    public final int main(String[] args, Terminal terminal) throws Exception {
        if (addShutdownHook()) {

            /**
             * 为了在程序结束时做一些收尾操作，比如资源释放等，这里直接调用了close方法
             * 所以可以在close中实现收尾动作
             * 
             */
            shutdownHookThread = new Thread(() -> {
                try {
                    this.close();
                } catch (final IOException e) {
                    try (
                        StringWriter sw = new StringWriter();
                        PrintWriter pw = new PrintWriter(sw)) {
                        e.printStackTrace(pw);
                        terminal.println(sw.toString());
                    } catch (final IOException impossible) {
                        // StringWriter#close declares a checked IOException from the Closeable interface but the Javadocs for StringWriter
                        // say that an exception here is impossible
                        throw new AssertionError(impossible);
                    }
                }
            });
            Runtime.getRuntime().addShutdownHook(shutdownHookThread);
        }

        beforeMain.run();

        try {
            mainWithoutErrorHandling(args, terminal);
        } catch (OptionException e) {
            printHelp(terminal);
            terminal.println(Terminal.Verbosity.SILENT, "ERROR: " + e.getMessage());
            return ExitCodes.USAGE;
        } catch (UserException e) {
            if (e.exitCode == ExitCodes.USAGE) {
                printHelp(terminal);
            }
            terminal.println(Terminal.Verbosity.SILENT, "ERROR: " + e.getMessage());
            return e.exitCode;
        }
        return ExitCodes.OK;
    }

    /**
     * Executes the command, but all errors are thrown.
     */
    void mainWithoutErrorHandling(String[] args, Terminal terminal) throws Exception {
        final OptionSet options = parser.parse(args);

        if (options.has(helpOption)) {
            printHelp(terminal);
            return;
        }

        if (options.has(silentOption)) {
            terminal.setVerbosity(Terminal.Verbosity.SILENT);
        } else if (options.has(verboseOption)) {
            terminal.setVerbosity(Terminal.Verbosity.VERBOSE);
        } else {
            terminal.setVerbosity(Terminal.Verbosity.NORMAL);
        }

        /**
         * 命令的执行逻辑函数，需要在具体命令类中实现。
         */
        execute(terminal, options);
    }

    /** Prints a help message for the command to the terminal. */
    private void printHelp(Terminal terminal) throws IOException {
        terminal.println(description);
        terminal.println("");
        printAdditionalHelp(terminal);
        parser.printHelpOn(terminal.getWriter());
    }

    /** Prints additional help information, specific to the command */
    protected void printAdditionalHelp(Terminal terminal) {}

    @SuppressForbidden(reason = "Allowed to exit explicitly from #main()")
    protected static void exit(int status) {
        System.exit(status);
    }

    /**
     * Executes this command.
     *
     * Any runtime user errors (like an input file that does not exist), should throw a {@link UserException}. */
    protected abstract void execute(Terminal terminal, OptionSet options) throws Exception;

    /**
     * Return whether or not to install the shutdown hook to cleanup resources on exit. This method should only be overridden in test
     * classes.
     *
     * @return whether or not to install the shutdown hook
     */
    protected boolean addShutdownHook() {
        return true;
    }

    /** Gets the shutdown hook thread if it exists **/
    Thread getShutdownHookThread() {
        return shutdownHookThread;
    }

    @Override
    public void close() throws IOException {

    }

}
