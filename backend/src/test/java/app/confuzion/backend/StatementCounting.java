/**
 * MIT License
 * <p>
 * Copyright (c) 2024 McAwful-Works
 * <p>
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 * <p>
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
 * Software.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package app.confuzion.backend;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Counts the statements the application prepares, by wrapping its
 * {@link DataSource}.
 *
 * @author mcawful
 */
@TestConfiguration(proxyBeanMethods = false)
public class StatementCounting {
	/**
	 * The statements prepared since the last reset.
	 */
	public static final AtomicInteger STATEMENTS = new AtomicInteger();

	/**
	 * Wraps the {@link DataSource} bean in a {@link CountingDataSource}.
	 *
	 * @return the {@link BeanPostProcessor}
	 */
	@Bean
	static BeanPostProcessor countStatements() {
		return new BeanPostProcessor() {
			/**
			 * Wraps a {@link DataSource} bean once, and passes every other bean through.
			 *
			 * @param bean     the bean
			 * @param beanName its name
			 * @return the bean, or its counting wrapper
			 */
			@Override
			public Object postProcessAfterInitialization(Object bean, String beanName) {
				return bean instanceof DataSource dataSource && !(bean instanceof CountingDataSource)
						? new CountingDataSource(dataSource)
						: bean;
			}
		};
	}

	/**
	 * A {@link DataSource} whose connections count every statement they prepare or
	 * create.
	 *
	 * @author mcawful
	 */
	private static final class CountingDataSource extends DelegatingDataSource {
		/**
		 * Wraps a data source.
		 *
		 * @param target the real {@link DataSource}
		 */
		CountingDataSource(DataSource target) {
			super(target);
		}

		/**
		 * Returns a counting connection from the real data source.
		 *
		 * @return the {@link Connection}
		 * @throws SQLException when the real data source fails
		 */
		@Override
		public Connection getConnection() throws SQLException {
			return counting(super.getConnection());
		}

		/**
		 * Returns a counting connection from the real data source.
		 *
		 * @param username the user name
		 * @param password the password
		 * @return the {@link Connection}
		 * @throws SQLException when the real data source fails
		 */
		@Override
		public Connection getConnection(String username, String password) throws SQLException {
			return counting(super.getConnection(username, password));
		}

		/**
		 * Wraps a connection so that preparing or creating a statement counts.
		 *
		 * @param connection the real {@link Connection}
		 * @return the counting {@link Connection}
		 */
		private static Connection counting(Connection connection) {
			return (Connection) Proxy.newProxyInstance(CountingDataSource.class.getClassLoader(),
					new Class<?>[] { Connection.class }, (proxy, method, args) -> {
						if (method.getName().startsWith("prepare") || method.getName().equals("createStatement"))
							STATEMENTS.incrementAndGet();
						try {
							return method.invoke(connection, args);
						} catch (InvocationTargetException e) {
							throw e.getCause();
						}
					});
		}
	}
}
