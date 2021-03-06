package com.ctrip.xpipe.lifecycle;

import java.util.Map;

import org.junit.Assert;
import org.junit.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.ctrip.xpipe.AbstractTest;
import com.ctrip.xpipe.api.lifecycle.ComponentRegistry;
import com.ctrip.xpipe.api.lifecycle.Lifecycle;
import com.ctrip.xpipe.api.lifecycle.TopElement;
import com.ctrip.xpipe.lifecycle.SpringComponentRegistry;

/**
 * @author wenchao.meng
 *
 * Jun 17, 2016
 */
public class SpringComponentRegistryTest extends AbstractTest{
	
	
	@Test
	public void test() throws Exception{
		
		ApplicationContext applicationContext = new AnnotationConfigApplicationContext(TestFactory.class);
		
		ComponentRegistry componentRegistry = new SpringComponentRegistry(applicationContext);

		componentRegistry.initialize();
		componentRegistry.start();
		
		Map<String, Lifecycle> objects = componentRegistry.getComponents(Lifecycle.class);
		for(Lifecycle lifecycle : objects.values()){
			
			if(lifecycle instanceof TopElement){
				Assert.assertTrue(lifecycle.getLifecycleState().isStarted());
			}else{
				Assert.assertTrue(lifecycle.getLifecycleState().isEmpty());
			}
		}
		
	}
	
	@Configuration
	public static class TestFactory{
		
		@Bean
		public Lifecycle create1(){
			return new NoOpLifecycleObject();
		}

		@Bean
		public Lifecycle create2(){
			return new TopNoOpLifecycleObject();
		}

		@Bean
		public Object create3(){
			return new Object();
		}
		
	}
	
	
	public static class TopNoOpLifecycleObject extends NoOpLifecycleObject implements TopElement{
		
	}
}
