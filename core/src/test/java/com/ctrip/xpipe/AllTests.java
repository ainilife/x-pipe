package com.ctrip.xpipe;





import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

import com.ctrip.xpipe.command.DefaultCommandFutureTest;
import com.ctrip.xpipe.endpoint.DefaultEndPointTest;
import com.ctrip.xpipe.endpoint.TestAbstractLifecycle;
import com.ctrip.xpipe.lifecycle.CreatedComponentRedistryTest;
import com.ctrip.xpipe.lifecycle.DefaultLifecycleControllerTest;
import com.ctrip.xpipe.lifecycle.DefaultLifecycleStateTest;
import com.ctrip.xpipe.lifecycle.SpringComponentRegistryTest;
import com.ctrip.xpipe.netty.commands.RequestResponseCommandTest;
import com.ctrip.xpipe.payload.ByteArrayOutputStreamPayloadTest;
import com.ctrip.xpipe.payload.ByteArrayWritableByteChannelTest;
import com.ctrip.xpipe.utils.OffsetNotifierTest;

/**
 * @author wenchao.meng
 *
 * May 17, 2016 2:09:41 PM
 */
@RunWith(Suite.class)
@SuiteClasses({
	DefaultCommandFutureTest.class,
	DefaultEndPointTest.class,
	ByteArrayOutputStreamPayloadTest.class,
	ByteArrayWritableByteChannelTest.class,
	TestAbstractLifecycle.class,
	DefaultLifecycleControllerTest.class,
	DefaultLifecycleStateTest.class,
	CreatedComponentRedistryTest.class,
	SpringComponentRegistryTest.class,
	OffsetNotifierTest.class,
	com.ctrip.xpipe.rest.RestRequestClientTest.class,
	RequestResponseCommandTest.class
	})
public class AllTests {

}
