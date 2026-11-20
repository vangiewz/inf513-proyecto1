package bo.uagrm.crm.persistencia;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/** Driver enteramente local: prueba frecuencia real de abrir(), sin sockets ni demoras reales. */
class ReconexionTest {
  static Object vacio(Class<?> tipo) {
    return Proxy.newProxyInstance(tipo.getClassLoader(),new Class<?>[]{tipo},(obj,m,args) -> {
      if (m.getName().equals("prepareStatement")) return vacio(PreparedStatement.class);
      if (m.getReturnType()==boolean.class) return false;
      if (m.getReturnType()==int.class) return 0;
      if (m.getReturnType()==long.class) return 0L;
      return null;
    });
  }

  static class DriverFalso implements Driver {
    final String url="jdbc:qa:tecnoweb.org.bo/"+UUID.randomUUID();
    final AtomicInteger intentos=new AtomicInteger();
    final String sqlState;
    DriverFalso(String sqlState) {this.sqlState=sqlState;}
    public boolean acceptsURL(String u) {return url.equals(u);}
    public Connection connect(String u,Properties p) throws SQLException {
      if (!acceptsURL(u)) return null;
      if (intentos.incrementAndGet()<=2) throw new SQLException("caída local simulada",sqlState);
      return (Connection)vacio(Connection.class);
    }
    public DriverPropertyInfo[] getPropertyInfo(String u,Properties p) {return new DriverPropertyInfo[0];}
    public int getMajorVersion(){return 1;} public int getMinorVersion(){return 0;}
    public boolean jdbcCompliant(){return false;} public Logger getParentLogger(){return Logger.getGlobal();}
  }

  @Test void noReconectaAntesDeTresSegundosYSeRecuperaAlVolver() throws Exception {
    AtomicLong reloj=new AtomicLong(); DriverFalso driver=new DriverFalso("08006");
    DriverManager.registerDriver(driver);
    try {
      DatabaseService db=new DatabaseService(driver.url,"qa","ficticia",reloj::get);
      assertThrows(SQLException.class,db::getConnection); assertEquals(1,driver.intentos.get());
      for(int i=0;i<20;i++) assertThrows(SQLException.class,db::getConnection);
      assertEquals(1,driver.intentos.get(),"no abre conexiones en un bucle rápido");
      reloj.set(2_999_000_000L); assertThrows(SQLException.class,db::getConnection);
      assertEquals(1,driver.intentos.get());
      reloj.set(3_000_000_000L); assertThrows(SQLException.class,db::getConnection);
      assertEquals(2,driver.intentos.get());
      reloj.set(6_000_000_000L); try(Connection c=db.getConnection()){assertNotNull(c);}
      assertEquals(3,driver.intentos.get());
      try(Connection c=db.getConnection()){assertNotNull(c);}
      assertEquals(4,driver.intentos.get(),"no introduce esperas entre transacciones normales disponibles");
    } finally {DriverManager.deregisterDriver(driver);}
  }

  @Test void credencialIncorrectaNoProvocaReconexionesRapidas() throws Exception {
    AtomicLong reloj=new AtomicLong(); DriverFalso driver=new DriverFalso("28P01");
    DriverManager.registerDriver(driver);
    try {
      DatabaseService db=new DatabaseService(driver.url,"qa","ficticia",reloj::get);
      assertThrows(SQLException.class,db::getConnection);
      reloj.set(59_999_000_000L); assertThrows(SQLException.class,db::getConnection);
      assertEquals(1,driver.intentos.get());
      reloj.set(60_000_000_000L); assertThrows(SQLException.class,db::getConnection);
      assertEquals(2,driver.intentos.get());
    } finally {DriverManager.deregisterDriver(driver);}
  }
}
