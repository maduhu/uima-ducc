package org.apache.uima.ducc.ws.server.nodeviz;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.apache.uima.ducc.common.Node;
import org.apache.uima.ducc.common.utils.DuccLogger;
import org.apache.uima.ducc.common.utils.DuccLoggerComponents;
import org.apache.uima.ducc.transport.event.common.IDuccTypes.DuccType;
import org.apache.uima.ducc.ws.MachineInfo;

class VisualizedHost
{

	private static DuccLogger logger = DuccLoggerComponents.getWsLogger(VisualizedHost.class.getName());
    private static FragmentSorter sorter = new FragmentSorter();

    // TODO:
    //   Some goodies - aliens, swap, heartbeat info, pubsize, expired, status
    String name;        // host name
    String ip;          // host ip
    int mem;            // actual mem as reported by agent
    int shares;         // q shares available on this host (constant)

    int shares_free;    // shares not used by jobs
    int mem_reservable; // schedulable (reservable) memory on this hosts

    int quantum;        // RM scheduling quantum

    List<JobFragment>  fragments = new ArrayList<JobFragment>();

    /**
     * Generate host from OR state which contains info about the work on a host.
     */
    VisualizedHost(Node n, int quantum)
    {
        this.quantum = quantum;
        this.name = strip(n.getNodeIdentity().getName());
        this.ip = n.getNodeIdentity().getIp();            
        
        // mem from OR pub is in KB.  must convert to GB
        this.mem =  (int) n.getNodeMetrics().getNodeMemory().getMemTotal() / ( 1024 * 1024 );

        this.shares = (mem / quantum);
        this.shares_free = shares;
        this.mem_reservable = shares * quantum;
    }

    /**
     * Generate host from agent publications because OR state only has host info for hosts with work on them.
     */
    VisualizedHost(MachineInfo info, int quantum)
    {
        this.quantum = quantum;

        this.name = strip(info.getName());
        this.ip = info.getIp();

        String ns = info.getSharesTotal();
        if ( ns == "" || ns == null ) {
            this.mem = 0;
            this.shares = 0;
            this.mem_reservable = 0;
        } else {
            this.mem = Integer.parseInt(info.getMemTotal());
            this.shares = (mem / quantum);
            this.mem_reservable = shares * quantum;
        }

        this.shares_free = shares;        
    }

    /**
     * Possibly strip domain name from hostname
     */
    String strip(String n)
    {
        if ( NodeViz.strip_domain ) {
            int ndx = n.indexOf(".");
            n = n.substring(0, ndx);
        }
        return n;
    }

    int countShares()
    {
        return shares;
    }

    int countRam()
    {
        return mem;
    }

    void addWork(DuccType type, String user, String duccid, int jobmem, int qshares, String service_endpoint)
    {
        String methodName = "addWork";

        // The job list is going to be short almost always, so cost of linear search will be less than the overhead
        // of maintaining a map - on the order of fewer than 5-6 items in worst case.  It's rare to see more than
        // 2-3 in real life.  If this should change so it's common to have more than about 10 elements in the list
        // we should switch to a map.

        logger.debug(methodName, null, name, "Set", qshares, "qshares for", name, type, duccid, ": mem", mem, "free qshares", shares_free, "from OR publication.");

        // if ( type == DuccType.Reservation ) qshares = shares_free;  // Trust the RM and the Force, Luke

        if ( shares_free - qshares < 0 ) {
            logger.warn(methodName, null, name, "SHARES FREE WENT NEGATIVE for", type, duccid, user, "qshares", qshares, "mem", mem, "shares_free", shares_free);
            return;
        } else {
            shares_free -= qshares;
        }

        boolean found = false;
        for ( JobFragment j : fragments ) {
            if ( j.matches(duccid) ) {
                j.addShares(qshares);
                logger.debug(methodName, null, name, "Update job fragment for", user, "with", qshares, "qshares", "total qshares", j.qshares);
                found = true;
                break;
            }
        }
        if ( ! found ) {
            logger.debug(methodName, null, name, "Create new job fragment for", user, "with", qshares, "qshare, type", type);
            JobFragment j = new JobFragment(user, type, duccid, jobmem, qshares, service_endpoint);
            fragments.add(j);
        }
    }

//     String getIp()                          { return ip; }
//     int    getMem()                         { return mem; }
//     int    getShares()                      { return shares; }

    float TITLE_ADJUSTMENT = 2f;        // Amount of space to add to each square at top to hold nodename
    void toSvg(Markup m)
    {
        String methodName = "toSvg  ";  // (extra spaces so logs line up better)

        if ( shares == 0 ) return;
        if ( shares_free > 0 ) addWork(DuccType.Undefined, "", "", 0, shares_free, null);

        float size = (float) Math.sqrt(mem);
        logger.debug(methodName, null, name, "mem =", mem, "size =", size);
            
        // here set a div that is TITLE_ADJUSTMENT higher and .2 wider than the actual node
        m.divStart();
        m.svgStart(size + TITLE_ADJUSTMENT, size + .2f);       // a bit taller than needed to make room for label
        // a bit wider, for horizontal spacing

        // here draw the box for the node, offset by TITLE_ADJUSTMENT from the top of the div
        m.rect(0f, TITLE_ADJUSTMENT, size, size, "white", "none", .1f, "");
        
        // here draw the node name just above the node box, including the hover
        m.tooltipStart(name + " (" + mem + "GB)");
        m.nodeLabel(0f, TITLE_ADJUSTMENT - .1f, name);
        m.tooltipEnd();
        
        Collections.sort(fragments, sorter);
        float height_one_share = (float) Math.sqrt(shares * NodeViz.quantum) / shares;
        float foo = (float) Math.sqrt(mem) / shares;
        logger.debug(methodName, null, name, "shares", shares, "height-one-share", height_one_share, "foo", foo);
        float top = 0f + TITLE_ADJUSTMENT;                   // the top of the box
        logger.debug(methodName, null, name, "Draw", fragments.size(), "rectangles, box size", size, "share height", height_one_share);


        for (JobFragment j : fragments ) {

            /**
             * Structure of this block.  Remembering that the link tag is a block level tag.
             *
             *    <a link-to-ws page for job>
             *       <title> tooltip stuff for the job fragment </title>
             *       <rect>  rectangle for job fragment </rect>
             *       <text>  text for job fragment (job id) </text>
             *   </a>
             */
            float height = j.qshares * height_one_share;
            logger.debug(methodName, null, name, "Draw box at", top, "of width", size, "height", height,  "at (0, " + top +") for", j.type, j.id, "shares", j.qshares);

            if ( top > (size + TITLE_ADJUSTMENT) ) {
                logger.warn(methodName, null, name, "Box overflow. Size", size, "top", top);
            }

            // generate the fill patern for reservations, services, MR, jobs
			String fill = m.patternedFill(j);

            // establish the link into the ws proper for each work type
            m.hyperlinkStart(this, j);

            // establish the tooltip for each fragment
            m.titleForFragment(this, j);

            // draw the share block for each fragment
            if ( j.type == DuccType.Undefined ) {
                m.rect(0, top, size, height, "", "black", .1f, "");
            } else {
                m.rect(0, top, size, height, fill, "black", .1f, "");
            }

            // draw the work duccid in the fragment
            m.text(.1f, top + 1.5f, j.id, j.textColor, 12, "");

            // close off the markup elements
            m.hyperlinkEnd();

            top += height;
        }
        
        m.svgEnd();
        m.divEnd();
    }

    static private class FragmentSorter
        implements Comparator<JobFragment>
    {
        public int compare(JobFragment f1, JobFragment f2)
        {
            if ( f1.type == DuccType.Undefined && f2.type != DuccType.Undefined) return 1;
            if ( f1.type != DuccType.Undefined && f2.type == DuccType.Undefined) return -1;
            return f2.qshares - f1.qshares;
        }
    }
    
}
