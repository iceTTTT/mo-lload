package io.mo.thread;

import io.mo.CONFIG;
import io.mo.conn.ConnectionOperation;
import io.mo.para.PreparedPara;
import io.mo.result.ExecResult;
import io.mo.result.RTBuffer;
import io.mo.transaction.PreparedSQLCommand;
import io.mo.transaction.SQLScript;
import io.mo.transaction.TransBuffer;
import io.mo.transaction.Transaction;
import org.apache.log4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CyclicBarrier;

public class TransExecutor implements Runnable {
    private static Logger LOG = Logger.getLogger(TransExecutor.class.getName());

    private TransBuffer transBuffer;

    private RTBuffer rtBuffer;

    private ExecResult execResult;

    private Connection connection;

    private Statement statement;

    private PreparedStatement[] ps;

    private boolean running = true;

    private String transName;

    private Transaction transaction;

    private CyclicBarrier barrier;
    
    private int id;

    public TransExecutor(int id , Connection connection,TransBuffer transBuffer,ExecResult execResult,CyclicBarrier barrier){
        this.transBuffer = transBuffer;
        this.transName = transBuffer.getTransaction().getName();

        this.execResult = execResult;
        this.execResult.increaseThread();

        this.rtBuffer = new RTBuffer(execResult);
        this.transaction = this.transBuffer.getTransaction();

        this.connection = connection;

        this.barrier = barrier;
        this.id = id;
        try {
            int count = transaction.getScript().length();
            ps = new PreparedStatement[count];
            if(transaction.isPrepared()){
                this.transaction = this.transaction.copy();
                for(int i = 0; i < count; i++) {
                    ps[i] = this.connection.prepareStatement(transaction.getScript().getPreparedCommand(i).getSql());
                    LOG.error(String.format("Thread[id=%d] has prepared [%s]",id,transaction.getScript().getPreparedCommand(i).getSql()));
                }
            }else {
                statement = this.connection.createStatement();
            }
            //this.connection.prepareStatement();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        String sql = null;
        //如果是事务模式，则启动事务执行
        if(transaction.getMode() == CONFIG.DB_TRANSACTION_MODE){

                //启动事务
            try {
                connection.setAutoCommit(false);
            } catch (SQLException e) {
                e.printStackTrace();
                System.exit(0);
            }
            
            if(!transaction.isPrepared()){
                while(!CONFIG.TIMEOUT) {
                    SQLScript script = transBuffer.getScript();

                    try {
                        long beginTime = System.currentTimeMillis();
                        //statement.execute("begin;");
                        for (int i = 0; i < script.length(); i++) {
                            boolean rs = statement.execute(script.getCommand(i));
                        }
                        //statement.execute("commit;");
                        connection.commit();
                        long endTime = System.currentTimeMillis();
                        //将执行时间和结果保存在临时缓冲区里
                        rtBuffer.setValue(transName + "=" + beginTime + ":" + endTime);
                    } catch (SQLException e) {
                        //LOG.error(e.getStackTrace());
                        try {
                            if(connection == null || connection.isClosed()) {
                                connection = ConnectionOperation.getConnection();
                                if(connection == null){
                                    running = false;
                                    rtBuffer.setValid(false);
                                    LOG.error(String.format("Thread[id=%d] can not get invalid connection after trying 3 times, and will exit",id));
                                    break;
                                }
                                statement = connection.createStatement();
                                connection.setAutoCommit(false);
                                execResult.setError(transName+":\r\n"+e.getMessage()+"\r\n");
                                continue;
                            }
                            //statement.execute("rollback;");
                            connection.rollback();
                        } catch (SQLException e1) {
                            e1.printStackTrace();
                        } catch (Exception ex) {
                            e.printStackTrace();
                        }
                        execResult.setError(transName+":\r\n"+e.getMessage()+"\r\n");
                        continue;
                    }
                }
            }
            
            else{
                SQLScript script = transaction.getScript();
                while(!CONFIG.TIMEOUT){
                    try {
                        long beginTime = System.currentTimeMillis();
                        for (int i = 0; i < script.length(); i++) {
                            PreparedSQLCommand command = script.getPreparedCommand(i);
                            PreparedPara[] paras = command.getPreparedParas();
                            
                            for (int j = 0; j < paras.length; j++) {
                                if (paras[j].isINT()) {
                                    ps[i].setInt(j + 1, paras[j].getIntValue());
                                }
                                if (paras[j].isSTR()) {
                                    ps[i].setString(j + 1, paras[j].getStrValue());
                                }
                                ps[i].execute();
                            }
                        }
                        long endTime = System.currentTimeMillis();
                        connection.commit();

                        //将执行时间和结果保存在临时缓冲区里
                        rtBuffer.setValue(transName+"="+beginTime+":"+endTime);
                    }catch (SQLException e){
                        try {
                            if(connection == null || connection.isClosed()) {
                                connection = ConnectionOperation.getConnection();
                                if(connection == null){
                                    running = false;
                                    rtBuffer.setValid(false);
                                    LOG.error(String.format("Thread[id=%d] can not get invalid connection after trying 3 times, and will exit",id));
                                    break;
                                }
                                connection.setAutoCommit(false);
                                for(int j = 0 ; j < ps.length; j++)
                                    ps[j] = connection.prepareStatement(transaction.getScript().getPreparedCommand(j).getSql());
                                execResult.setError(transName+":\r\n"+e.getMessage()+"\r\n");
                                continue;
                            }
                            connection.rollback();
                        } catch (SQLException e1) {
                            e1.printStackTrace();
                        } catch (Exception ex) {
                            e.printStackTrace();
                        }
                        execResult.setError(transName+":\r\n"+e.getMessage()+"\r\n");
                    }
                    
//                    try {
//                        for (int i = 0; i < paraSize; i++) {
//                            for(int j = 0 ; j < ps.length; j++){
//                                if(paras[i].isINT()){
//                                    ps[j].setInt(i+1,paras[i].getIntValue());
//                                }
//                                if(paras[i].isSTR()){
//                                    ps[j].setString(i+1,paras[i].getStrValue());
//                                }
//                            }
//                        }
//
//                        long beginTime = System.currentTimeMillis();
//                        for(int j = 0 ; j < ps.length; j++)
//                            ps[j].execute();
//                        long endTime = System.currentTimeMillis();
//                        connection.commit();
//                        
//                        //将执行时间和结果保存在临时缓冲区里
//                        rtBuffer.setValue(transName+"="+beginTime+":"+endTime);
//
//                    }catch (SQLException e){
//                        try {
//                            if(connection == null || connection.isClosed()) {
//                                connection = ConnectionOperation.getConnection();
//                                if(connection == null){
//                                    running = false;
//                                    rtBuffer.setValid(false);
//                                    LOG.error(String.format("Thread[id=%d] can not get invalid connection after trying 3 times, and will exit",id));
//                                    break;
//                                }
//                                connection.setAutoCommit(false);
//                                for(int j = 0 ; j < ps.length; j++)
//                                    ps[j] = connection.prepareStatement(transaction.getScript().getCommand(j));
//                                execResult.setError(transName+":\r\n"+e.getMessage()+"\r\n");
//                                continue;
//                            }
//                            connection.rollback();
//                        } catch (SQLException e1) {
//                            e1.printStackTrace();
//                        } catch (Exception ex) {
//                            e.printStackTrace();
//                        }
//                        execResult.setError(transName+":\r\n"+e.getMessage()+"\r\n");
//                        continue;
//                    }
                }
            }
        }
        
        else {
            //如果是非事务模式，直接执行
            //如果没有prepare
            if(!transaction.isPrepared()){

                while(!CONFIG.TIMEOUT){
                    SQLScript script = transBuffer.getScript();
                    System.out.println(script.length());
                    try {
                        long beginTime = System.currentTimeMillis();
                        for(int i = 0; i < script.length();i++){
                            boolean rs = statement.execute(script.getCommand(i));
                        }
                        long endTime = System.currentTimeMillis();

                        //将执行时间和结果保存在临时缓冲区里
                        rtBuffer.setValue(transName+"="+beginTime+":"+endTime);

                    } catch (SQLException e) {
                        try {
                            if(connection == null || connection.isClosed()) {
                                connection = ConnectionOperation.getConnection();
                                if(connection == null){
                                    running = false;
                                    rtBuffer.setValid(false);
                                    LOG.error(String.format("Thread[id=%d] can not get invalid connection after trying 3 times, and will exit",id));
                                    break;
                                }
                                statement = connection.createStatement();
                                execResult.setError(transName+":\r\n"+e.getMessage()+"\r\n");
                                continue;
                            }
                        } catch (SQLException e1) {
                            e1.printStackTrace();
                        } catch (Exception ex) {
                            e.printStackTrace();
                        }
                        execResult.setError(transName+":\r\n"+e.getMessage()+"\r\n");
                        continue;
                    }
                }
            }
            
            else {

                SQLScript script = transBuffer.getScript();
                while(!CONFIG.TIMEOUT){
                    try {
                        long beginTime = System.currentTimeMillis();
                        for (int i = 0; i < script.length(); i++) {
                            PreparedSQLCommand command = script.getPreparedCommand(i);
                            PreparedPara[] paras = command.getPreparedParas();

                            for (int j = 0; j < paras.length; j++) {
                                if (paras[j].isINT()) {
                                    ps[i].setInt(j + 1, paras[j].getIntValue());
                                }
                                if (paras[j].isSTR()) {
                                    ps[i].setString(j + 1, paras[j].getStrValue());
                                }
                                ps[i].execute();
                            }
                        }
                        long endTime = System.currentTimeMillis();

                        //将执行时间和结果保存在临时缓冲区里
                        rtBuffer.setValue(transName+"="+beginTime+":"+endTime);
                    }catch (SQLException e){
                        try {
                            if(connection == null || connection.isClosed()) {
                                connection = ConnectionOperation.getConnection();
                                if(connection == null){
                                    running = false;
                                    rtBuffer.setValid(false);
                                    LOG.error(String.format("Thread[id=%d] can not get invalid connection after trying 3 times, and will exit",id));
                                    break;
                                }
                                connection.setAutoCommit(false);
                                for(int j = 0 ; j < ps.length; j++)
                                    ps[j] = connection.prepareStatement(transaction.getScript().getPreparedCommand(j).getSql());
                                execResult.setError(transName+":\r\n"+e.getMessage()+"\r\n");
                                continue;
                            }
                        } catch (SQLException e1) {
                            e1.printStackTrace();
                        } catch (Exception ex) {
                            e.printStackTrace();
                        }
                        execResult.setError(transName+":\r\n"+e.getMessage()+"\r\n");
                    }
                    
//                int paraSize = transaction.getParasList().length;
//                PreparedPara[] paras = transaction.getParasList();
//                while(!CONFIG.TIMEOUT){
//                    try {
//                        for (int i = 0; i < paraSize; i++) {
//                            for(int j = 0 ; j < ps.length; j++){
//                                if(paras[i].isINT()){
//                                    ps[j].setInt(i+1,paras[i].getIntValue());
//                                }
//                                if(paras[i].isSTR()){
//                                    ps[j].setString(i+1,paras[i].getStrValue());
//                                }
//                            }
//                        }
//
//                        long beginTime = System.currentTimeMillis();
//                        for(int j = 0 ; j < ps.length; j++)
//                            ps[j].execute();
//                        long endTime = System.currentTimeMillis();
//
//                        //将执行时间和结果保存在临时缓冲区里
//                        rtBuffer.setValue(transName+"="+beginTime+":"+endTime);
//
//                    }catch (SQLException e){
//                        try {
//                            if(connection == null || connection.isClosed()) {
//                                connection = ConnectionOperation.getConnection();
//                                if(connection == null){
//                                    running = false;
//                                    rtBuffer.setValid(false);
//                                    LOG.error(String.format("Thread[id=%d] can not get invalid connection after trying 3 times, and will exit",id));
//                                    break;
//                                }
//                                for(int j = 0 ; j < ps.length; j++)
//                                    ps[j] = connection.prepareStatement(transaction.getScript().getCommand(j));
//                                execResult.setError(transName+":\r\n"+e.getMessage()+"\r\n");
//                                continue;
//                            }
//                        } catch (SQLException e1) {
//                            e1.printStackTrace();
//                        } catch (Exception ex) {
//                            e.printStackTrace();
//                        }
//                        execResult.setError(transName+":\r\n"+e.getMessage()+"\r\n");
//                        continue;
//                    }
                }
            }
        }

        running = false;
        rtBuffer.setValid(false);
    }
}
