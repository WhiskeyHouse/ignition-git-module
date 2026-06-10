package com.axone_io.ignition.git;

import com.inductiveautomation.ignition.client.gateway_interface.GatewayConnection;
import com.inductiveautomation.ignition.common.rpc.proto.ProtoRpcSerializer;
import com.inductiveautomation.ignition.common.script.ScriptManager;
import com.inductiveautomation.ignition.common.script.hints.PropertiesFileDocProvider;
import com.inductiveautomation.vision.api.client.AbstractClientModuleHook;

public class ClientHook extends AbstractClientModuleHook {

    private volatile GitScriptInterface rpc;

    // Lazily acquire the RPC proxy: the gateway connection is not available when
    // initializeScriptManager runs, only once the client session is up.
    private GitScriptInterface rpc() {
        GitScriptInterface local = rpc;
        if (local == null) {
            synchronized (this) {
                local = rpc;
                if (local == null) {
                    rpc = local = GatewayConnection.getRpcInterface(
                            ProtoRpcSerializer.DEFAULT_INSTANCE,
                            "com.axone_io.ignition.git",
                            GitScriptInterface.class
                    );
                }
            }
        }
        return local;
    }

    @Override
    public void initializeScriptManager(ScriptManager manager) {
        super.initializeScriptManager(manager);

        manager.addScriptModule(
                "system.git",
                new GitScriptFunctions(this::rpc),
                new PropertiesFileDocProvider()
        );
    }

}
