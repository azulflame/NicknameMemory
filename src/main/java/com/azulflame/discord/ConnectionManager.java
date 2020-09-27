package com.azulflame.discord;/*
 * Created by Todd on 9/24/2020.
 */

import java.sql.*;

public class ConnectionManager
{
	private static String connectionString;
	private static String username;
	private static String password;
	private static Connection conn;

	private static int numFailures;

	public static void init(String inputConnectionString, String user, String pass) throws SQLException
	{
		connectionString = inputConnectionString;
		username = user;
		password = pass;
		numFailures = 0;
		reconnect();
	}

	private static void reconnect() throws SQLException
	{
		conn = DriverManager.getConnection(connectionString, username, password);
	}

	public static Connection getConnection() throws SQLException
	{
		try
		{
			// run a quick validation query
			Statement connectionTest = conn.createStatement();
			ResultSet rs = connectionTest.executeQuery("SELECT 1");
			if(rs.next())
			{
				numFailures = 0;
				return conn;
			}
		}
		catch(SQLException throwables)
		{
			// if the validation query fails, attempt to reconnect up to 5 times
			if(numFailures < 5)
			{
				reconnect();
				return getConnection();
			}
			else
			{
				numFailures++;
			}
		}
		throw new SQLTimeoutException();
	}
}
