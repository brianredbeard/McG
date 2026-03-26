/*
* Copyright 2024 - 2024 the original author or authors.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* https://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.suidpit;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.SwingUtilities;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import ghidra.app.decompiler.DecompInterface;
import ghidra.program.flatapi.FlatProgramAPI;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Program;
import ghidra.program.model.listing.Variable;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.SourceType;
import ghidra.program.util.string.FoundString;

@Service
public class GhidraService {

    private GhidraMCPPlugin getPlugin() {
        GhidraMCPPlugin plugin = McpServerApplication.getActivePlugin();
        if (plugin == null) {
            throw new IllegalStateException("No Ghidra plugin instance available");
        }
        return plugin;
    }

    private Program getProgram() {
        Program program = getPlugin().getCurrentProgram();
        if (program == null) {
            throw new IllegalStateException("No program is open in the active CodeBrowser");
        }
        return program;
    }

    private Function requireFunction(String functionName) {
        for (Function function : getProgram().getFunctionManager().getFunctions(true)) {
            if (function.getName().equals(functionName)) {
                return function;
            }
        }
        throw new IllegalArgumentException("Function not found: " + functionName);
    }

    private Address requireAddress(String address) {
        Address addr = getProgram().getAddressFactory().getAddress(address);
        if (addr == null) {
            throw new IllegalArgumentException("Invalid address: " + address);
        }
        return addr;
    }

    /**
     * Runs a program modification on the Swing EDT inside a transaction.
     * Propagates any error to the caller instead of swallowing it.
     */
    private void runTransaction(String description, Runnable action) {
        AtomicReference<Exception> error = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                Program program = getProgram();
                int tx = program.startTransaction(description);
                boolean success = false;
                try {
                    action.run();
                    success = true;
                } catch (Exception e) {
                    error.set(e);
                } finally {
                    program.endTransaction(tx, success);
                }
            });
        } catch (InterruptedException | InvocationTargetException e) {
            throw new RuntimeException("Failed to execute on Swing thread: " + e.getMessage(), e);
        }
        if (error.get() != null) {
            Exception e = error.get();
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Tool(description = "List all open programs across all CodeBrowser windows. Shows which program is currently active.")
    public List<String> listOpenPrograms() {
        return McpServerApplication.getOpenPrograms();
    }

    @Tool(description = "Select which open program to operate on by name. Use listOpenPrograms to see available programs.")
    public String selectProgram(String programName) {
        if (McpServerApplication.selectProgram(programName)) {
            return "Selected program: " + programName;
        }
        throw new IllegalArgumentException("Program not found: " + programName
                + ". Use listOpenPrograms to see available programs.");
    }

    @Tool(description = "List all functions in the current program")
    public List<String> listFunctions() {
        var functionNames = new ArrayList<String>();
        for (Function function : getProgram().getFunctionManager().getFunctions(true)) {
            functionNames.add(function.getName());
        }
        return functionNames;
    }

    @Tool(description = "Get function entry point address by name")
    public String getFunctionAddressByName(String functionName) {
        return requireFunction(functionName).getEntryPoint().toString();
    }

    @Tool(description = "Decompile a function to C pseudocode by name")
    public String decompileFunctionByName(String functionName) {
        var function = requireFunction(functionName);
        var decompInterface = new DecompInterface();
        decompInterface.openProgram(getProgram());
        var decompiled = decompInterface.decompileFunction(function, 30, null);
        if (decompiled == null || !decompiled.decompileCompleted()) {
            throw new RuntimeException("Decompilation failed for function: " + functionName);
        }
        return decompiled.getDecompiledFunction().getC();
    }

    @Tool(description = "Rename a function")
    public String renameFunction(String currentName, String newName) {
        runTransaction("Rename function", () -> {
            var function = requireFunction(currentName);
            try {
                function.setName(newName, SourceType.USER_DEFINED);
            } catch (Exception e) {
                throw new RuntimeException("Failed to set function name: " + e.getMessage(), e);
            }
        });
        return "Renamed " + currentName + " to " + newName;
    }

    @Tool(description = "Add a comment to a function")
    public String addCommentToFunction(String functionName, String comment) {
        runTransaction("Add comment to function", () -> {
            requireFunction(functionName).setComment(comment);
        });
        return "Comment added to " + functionName;
    }

    @Tool(description = "Rename a local variable within a function")
    public String renameLocalVariableInFunction(String functionName, String currentVariableName,
            String newVariableName) {
        runTransaction("Rename local variable in function", () -> {
            var function = requireFunction(functionName);
            for (Variable var : function.getAllVariables()) {
                if (var.getName().equals(currentVariableName)) {
                    try {
                        var.setName(newVariableName, SourceType.USER_DEFINED);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to rename variable: " + e.getMessage(), e);
                    }
                    return;
                }
            }
            throw new IllegalArgumentException(
                    "Variable " + currentVariableName + " not found in function " + functionName);
        });
        return "Renamed variable " + currentVariableName + " to " + newVariableName;
    }

    @Tool(description = "Get references to an address. Returns addresses and code units that reference the given address.")
    public List<String> getReferencesToAddress(String address) {
        var addr = requireAddress(address);
        var references = getProgram().getReferenceManager().getReferencesTo(addr);
        var result = new ArrayList<String>();
        for (Reference ref : references) {
            var codeUnit = getProgram().getListing().getCodeUnitAt(ref.getFromAddress());
            result.add(ref.getFromAddress().toString() + " | "
                    + (codeUnit != null ? codeUnit.toString() : "unknown"));
        }
        return result;
    }

    @Tool(description = "Get references from an address. Returns addresses and code units that are referenced from the given address.")
    public List<String> getReferencesFromAddress(String address) {
        var addr = requireAddress(address);
        var references = getProgram().getReferenceManager().getReferencesFrom(addr);
        var result = new ArrayList<String>();
        for (Reference ref : references) {
            var codeUnit = getProgram().getListing().getCodeUnitAt(ref.getToAddress());
            result.add(ref.getToAddress().toString() + " | "
                    + (codeUnit != null ? codeUnit.toString() : "unknown"));
        }
        return result;
    }

    @Tool(description = "Get functions that call the given function")
    public List<String> getFunctionCallers(String functionName) {
        var function = requireFunction(functionName);
        var program = getProgram();
        var references = program.getReferenceManager().getReferencesTo(function.getEntryPoint());
        var result = new ArrayList<String>();
        for (Reference ref : references) {
            var caller = program.getFunctionManager().getFunctionContaining(ref.getFromAddress());
            if (caller != null) {
                result.add(caller.getName());
            }
        }
        return result;
    }

    @Tool(description = "Search for strings in the program containing the query. Minimum string length is 5 characters.")
    public List<String> searchForStrings(String query) {
        var program = getProgram();
        var result = new ArrayList<String>();
        FlatProgramAPI flatProgramAPI = new FlatProgramAPI(program);
        var foundStrings = flatProgramAPI.findStrings(null, 5, 1, true, true);
        for (FoundString foundString : foundStrings) {
            String value = foundString.getString(program.getMemory());
            if (value.contains(query)) {
                result.add(foundString.getAddress().toString() + " | " + value);
            }
        }
        return result;
    }
}
